/**
 * Copyright 2010-2017 interactive instruments GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.interactive_instruments.etf.webapp.controller;

import static de.interactive_instruments.etf.webapp.SwaggerConfig.TEST_RESULTS_TAG_NAME;
import static de.interactive_instruments.etf.webapp.SwaggerConfig.TEST_RUNS_TAG_NAME;
import static de.interactive_instruments.etf.webapp.WebAppConstants.API_BASE_URL;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import de.interactive_instruments.SUtils;
import de.interactive_instruments.TimedExpiredItemsRemover;
import de.interactive_instruments.etf.EtfConstants;
import de.interactive_instruments.etf.dal.dao.Dao;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.run.TestRunDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.testdriver.*;
import de.interactive_instruments.etf.webapp.conversion.EidConverter;
import de.interactive_instruments.etf.webapp.dto.StartTestRunRequest;
import de.interactive_instruments.exceptions.ExcUtils;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.StorageException;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import io.swagger.annotations.*;

/**
 * Test run controller for starting and monitoring test runs
 */
@RestController
public class TestRunController implements TestRunEventListener {

	@Autowired
	DataStorageService dataStorageService;
	@Autowired
	private TestDriverController testDriverController;
	@Autowired
	private TestObjectController testObjectController;
	@Autowired
	private TestResultController testResultController;
	private Timer timer;
	@Autowired
	private EtfConfigController etfConfig;

	@Autowired
	private StreamingService streamingService;

	boolean simplifiedWorkflows;
	private Dao<TestRunDto> testRunDao;

	private final static String TEST_RUNS_URL = API_BASE_URL + "/TestRuns";

	public final static int MAX_PARALLEL_RUNS = Runtime.getRuntime().availableProcessors();

	private final TaskPoolRegistry<TestRunDto, TestRun> taskPoolRegistry = new TaskPoolRegistry<>(MAX_PARALLEL_RUNS,
			MAX_PARALLEL_RUNS);
	private final Logger logger = LoggerFactory.getLogger(TestRunController.class);

	public TestRunController() {}

	final static class TaskProgressDto {

		@ApiModelProperty(value = "Completed Test Steps", example = "39", dataType = "int")
		private String val;
		@ApiModelProperty(value = "Maximum number of Test Steps", example = "103", dataType = "int")
		private String max;
		@ApiModelProperty(value = "Log messages", example = "[ \"Test Run started\", \"Assertion X failed\"]")
		private List<String> log;

		// Completed
		private TaskProgressDto(String max, List<String> log) {
			this.val = max;
			this.max = max;
			this.log = log;
		}

		public TaskProgressDto() {}

		static TaskProgressDto createCompletedMsg(TaskProgress p) {
			return new TaskProgressDto(
					String.valueOf(p.getMaxSteps()), new ArrayList<>());
		}

		static TaskProgressDto createAlreadyCompleted() {
			return new TaskProgressDto(String.valueOf(100), new ArrayList<String>(1) {
				{
					add("Already completed");
				}
			});
		}

		static TaskProgressDto createTerminateddMsg(int max) {
			return new TaskProgressDto(String.valueOf(max), new ArrayList<String>(1) {
				{
					add("Terminated");
				}
			});
		}

		// Still running
		private TaskProgressDto(final TaskProgress p, final long pos) {
			this.val = String.valueOf(p.getCurrentStepsCompleted());
			if (p.getCurrentStepsCompleted() >= p.getMaxSteps()) {
				this.max = String.valueOf(p.getMaxSteps() + p.getCurrentStepsCompleted());
			} else {
				this.max = String.valueOf(p.getMaxSteps());
			}
			this.log = p.getLogReader().getLogMessages(pos);
		}

		public String getVal() {
			return val;
		}

		public String getMax() {
			return max;
		}

		public List<String> getLog() {
			return log;
		}
	}

	@ApiModel(description = "Simplified Test Run view")
	private static class TestRunsJsonView {
		@ApiModelProperty(value = EID_DESCRIPTION, example = EID_EXAMPLE)
		public final String id;

		@ApiModelProperty(value = TEST_RUN_LABEL_DESCRIPTION, example = TEST_RUN_LABEL_EXAMPLE)
		public final String label;

		@ApiModelProperty(value = "Number of Test Tasks. " + TEST_TASK_DESCRIPTION, example = "3", dataType = "int")
		public final int testTaskCount;

		@ApiModelProperty(value = "Start timestamp in milliseconds, measured between the time the test run was started"
				+ " and midnight, January 1, 1970 UTC(coordinated universal time).", example = "1488469744783")
		public final Date startTimestamp;

		@ApiModelProperty(value = "Percentage of overall completed Test Steps", example = "0.879")
		public final double percentStepsCompleted;

		public TestRunsJsonView(final TestRun t) {
			id = t.getId().getId();
			label = t.getLabel();
			testTaskCount = t.getTestTasks().size();
			startTimestamp = t.getProgress().getStartTimestamp();
			percentStepsCompleted = t.getProgress().getPercentStepsCompleted();
		}
	}

	@PostConstruct
	public void init() throws ParseException, ConfigurationException, IOException, StorageException {
		logger.info(Runtime.getRuntime().availableProcessors() + " cores available.");

		// SEL dir
		System.setProperty("ETF_SEL_GROOVY",
				etfConfig.getPropertyAsFile(EtfConstants.ETF_PROJECTS_DIR).expandPath("sui").getPath());
		simplifiedWorkflows = "simplified".equals(etfConfig.getProperty(EtfConfigController.ETF_WORKFLOWS));
		testRunDao = dataStorageService.getDao(TestRunDto.class);

		timer = new Timer(true);
		// Trigger every 30 Minutes
		TimedExpiredItemsRemover timedExpiredItemsRemover = new TimedExpiredItemsRemover();
		timedExpiredItemsRemover.addExpirationItemHolder(
				(l, timeUnit) -> taskPoolRegistry.removeDone(),
				0, TimeUnit.HOURS);
		// 7,5 minutes
		timer.scheduleAtFixedRate(timedExpiredItemsRemover, 450000, 450000);

		logger.info("Test Run controller initialized!");
	}

	@PreDestroy
	public void shutdown() {
		logger.info("Shutting down TestRunController");
		if (this.timer != null) {
			timer.cancel();
		}
	}

	void addMetaData(final Model model) {
		model.addAttribute("testRuns", taskPoolRegistry.getTasks());
		model.addAttribute("testDriversInfo", testDriverController.getTestDriverInfo());
	}

	private void initAndSubmit(TestRunDto testRunDto) throws LocalizableApiError {
		try {
			final TestRun testRun = testDriverController.create(testRunDto);
			Objects.requireNonNull(testRun, "Test Driver created invalid TestRun").addTestRunEventListener(this);
			testRun.init();

			// Check if the test object has changed since the last run
			// and update the test object
			// todo
			/*
			final TestObject tO = testRunTask.getTestRun().getTestObject();
			if (testRunTask.getTestRun().isTestObjectResourceUpdateRequired() &&
					testObjectController.getTestObjStore().exists(tO.getId())) {
				testObjectController.getTestObjStore().update(tO);
			}
			*/
			testResultController.storeTestRun(testRunDto);
			logger.info("TestRun " + testRunDto.getDescriptiveLabel() + " initialized");
			taskPoolRegistry.submitTask(testRun);
		} catch (Exception e) {
			throw new LocalizableApiError(
					"l.internal.testrun.initialization.error",
					true, 500, e);
		}
	}

	@Override
	public void taskStateChangedEvent(final TestTask testTask, final TaskState.STATE current, final TaskState.STATE old) {
		logger.trace("TaskStateChanged event received from Test Task {} : {} -> {}", testTask.getId(),
				old == null ? "first light" : old, current);

	}

	@Override
	public void taskRunChangedEvent(final TestRun testRun, final TaskState.STATE current, final TaskState.STATE old) {
		logger.trace("TaskStateChanged event received from Test Run {} : {} -> {} (Test Run label: {})", testRun.getId(),
				old == null ? "first light" : old, current, testRun.getLabel());
		if (current.isCompleted()) {
			try {
				testResultController.updateTestRun(testRun);
			} catch (StorageException | ObjectWithIdNotFoundException e) {
				final String identifier = testRun != null ? testRun.getLabel() : "";
				logger.error("Test Run " + identifier + " could not be updated");
			}
		}
	}

	//
	// Rest interfaces
	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	@ApiOperation(value = "Get the Test Run progress as JSON", notes = "Retrieve one Test Run status including log messages, the number of executed and remaining Test Steps", produces = "application/json", tags = {
			TEST_RUNS_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 200, message = "Task progress returned", response = TaskProgressDto.class),
			@ApiResponse(code = 404, message = "Test Run not found", response = Void.class),
	})
	@RequestMapping(value = API_BASE_URL + "/TestRuns/{id}/progress", method = RequestMethod.GET)
	@ResponseBody
	public TaskProgressDto progressLog(
			@ApiParam(value = "Test Run ID. "
					+ EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
			@ApiParam(value = "The position in the logs from where to resume. "
					+ "The client shall submit his current cached log message size to this interface, "
					+ "so that the service can skip the known messages and return only new ones. "
					+ "Example: the client received 3 log messages and shall therefore invoke this interface with pos=3. "
					+ "In the meantime the service logged a total of 13 messages. As the client knows the first three "
					+ "messages the service will skip the first 3 messages and return the 10 new messages.", example = "13", required = false) @RequestParam(value = "pos", required = false) String strPos,
			final HttpServletResponse response) throws StorageException {

		long position = 0;
		if (!SUtils.isNullOrEmpty(strPos)) {
			position = Long.valueOf(strPos);
			if (position < 0) {
				position = 0;
			}
		}

		final TestRun testRun;
		final EID eid = EidConverter.toEid(id);
		try {
			testRun = taskPoolRegistry.getTaskById(eid);
		} catch (ObjectWithIdNotFoundException e) {
			if (testRunDao.exists(eid)) {
				logger.info("Notifying web client about already finished Test Run");
				return TaskProgressDto.createAlreadyCompleted();
			} else {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			}
			return null;
		}
		final TaskState.STATE state = testRun.getState();

		if (state == TaskState.STATE.FAILED || state == TaskState.STATE.CANCELED) {
			// Log the internal error and release the task
			try {
				testRun.waitForResult();
			} catch (Exception e) {
				logger.error("TestRun failed with an internal error", e);
				taskPoolRegistry.release(EidConverter.toEid(id));
			}
		} else if (state.isCompleted() || state.isFinalizing()) {
			// The Client should already be informed, that the task finished, but just send again
			// JSON, which indicates that the task has been completed (with val==max)
			try {
				Thread.sleep(1500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			logger.info("Test Run completed, notifying web client");
			return TaskProgressDto.createCompletedMsg(testRun.getProgress());
		} else {
			// Return updated information
			return new TaskProgressDto(testRun.getProgress(), position);
		}

		// The task is running, but does not provide any new information, so just respond
		// with an empty obj
		return new TaskProgressDto();
	}

	@ApiOperation(value = "Get the progress of all Test Runs", notes = "Retrieve status information about all non-completed Test Runs", tags = {
			TEST_RUNS_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 200, message = "OK"),
	})
	@RequestMapping(value = API_BASE_URL + "/TestRuns", params = "view=progress", method = RequestMethod.GET)
	public @ResponseBody List<TestRunsJsonView> listTestRunsJson() throws StorageException, ConfigurationException {
		final List<TestRunsJsonView> testRunsJsonViews = new ArrayList<TestRunsJsonView>();
		taskPoolRegistry.getTasks().forEach(t -> testRunsJsonViews.add(new TestRunsJsonView(t)));
		return testRunsJsonViews;
	}

	@ApiOperation(value = "Check if the Test Run exists", notes = "Checks whether a Test Run is running or has already been completed and a report has been saved. ", tags = {
			TEST_RESULTS_TAG_NAME, TEST_RUNS_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 204, message = "Test Run exists", response = Void.class),
			@ApiResponse(code = 404, message = "Test Run does not exist", response = Void.class),
	})
	@RequestMapping(value = {TEST_RUNS_URL + "/{id}"}, method = RequestMethod.HEAD)
	public ResponseEntity exists(
			@ApiParam(value = "Test Run ID. "
					+ EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id)
			throws StorageException {
		final EID eid = EidConverter.toEid(id);
		return taskPoolRegistry.contains(eid) || testRunDao.exists(eid) ? new ResponseEntity(HttpStatus.NO_CONTENT)
				: new ResponseEntity(HttpStatus.NOT_FOUND);
	}

	@ApiOperation(value = "Cancel and delete a Test Run", notes = "Cancels a running Test Run or deletes an already completed and saved report.", response = Void.class, tags = {
			TEST_RESULTS_TAG_NAME, TEST_RUNS_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 204, message = "Test Run deleted", responseHeaders = {
					@ResponseHeader(name = "action", response = String.class, description = "Set to 'canceled' if the Test Run was canceled or "
							+ "'deleted' if a persisted Test Run was removed")}),
			@ApiResponse(code = 404, message = "Test Run not found", response = RestExceptionHandler.ApiError.class)
	})
	@RequestMapping(value = TEST_RUNS_URL + "/{id}", method = RequestMethod.DELETE)
	public ResponseEntity delete(
			@ApiParam(value = "Test Run ID. "
					+ EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id)
			throws LocalizableApiError {
		final EID eid = EidConverter.toEid(id);
		final HttpHeaders responseHeaders = new HttpHeaders();
		try {
			if (taskPoolRegistry.contains(eid)) {
				responseHeaders.set("action", "canceled");
				taskPoolRegistry.cancelTask(eid);
				try {
					((WriteDao) testRunDao).delete(eid);
				} catch (ObjectWithIdNotFoundException | StorageException ignore) {
					ExcUtils.suppress(ignore);
				}
				return new ResponseEntity(responseHeaders, HttpStatus.NO_CONTENT);
			} else if (testRunDao.exists(EidConverter.toEid(id))) {
				responseHeaders.set("action", "deleted");
				((WriteDao) testRunDao).delete(eid);
				return new ResponseEntity(responseHeaders, HttpStatus.NO_CONTENT);
			}
		} catch (ObjectWithIdNotFoundException e) {
			throw new LocalizableApiError(e);
		} catch (StorageException e) {
			throw new LocalizableApiError(e);
		}
		return new ResponseEntity(HttpStatus.NOT_FOUND);
	}

	@ApiOperation(value = "Start a new Test Run", notes = "Start a new Test Run by specifying one or multiple Executable Test Suites "
			+ "that shall be used to test one Test Object with specified test parameters. "
			+ "If data for a Test Object need to be uploaded, the Test Object POST interface "
			+ "needs to be used to create a new temporary Test Object. "
			+ "The temporary Test Object or any other existing Test Object can be referenced by "
			+ "setting exclusively the 'id' in the StartTestRunRequest's 'testObject' property. "
			+ "If data do not need to be uploaded or a web service is tested, a temporary Test Object "
			+ "can be created directly with this interface, by defining at least the "
			+ "'resources' property of the 'testObject' but omit except the 'id' property."
			+ "\n\n"
			+ "Example for starting a Test Run for a service Test:  <br/>"
			+ "\n\n"
			+ "    {\n"
			+ "        \"label\": \"Test run on 15:00 - 01.01.2017 with Conformance class Conformance Class: Download Service - Pre-defined WFS\",\n"
			+ "        \"executableTestSuiteIds\": [\"EID174edf55-699b-446c-968c-1892a4d8d5bd\"],\n"
			+ "        \"arguments\": {},\n"
			+ "        \"testObject\": {\n"
			+ "            \"resources\": {\n"
			+ "                \"serviceEndpoint\": \"http://example.com/service?request=GetCapabilities\"\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "\n\n"
			+ "Example for starting a Test Run for a file-based Test, using a temporary Test Object:<br/>"
			+ "\n\n"
			+ "    {\n"
			+ "        \"label\": \"Test run on 15:00 - 01.01.2017 with Conformance class INSPIRE Profile based on EN ISO 19115 and EN ISO 19119\",\n"
			+ "        \"executableTestSuiteIds\": [\"EIDec7323d5-d8f0-4cfe-b23a-b826df86d58c\"],\n"
			+ "        \"arguments\": {\n"
			+ "            \"files_to_test\": \".*\",\n"
			+ "            \"tests_to_execute\": \".*\"\n"
			+ "        },\n"
			+ "        \"testObject\": {\n"
			+ "            \"id\": \"b502260f-1054-432e-8cd5-4a61302dfdba\"\n"
			+ "        }\n"
			+ "    }\n"
			+ "\n\n"
			+ "Where \"EIDb502260f-1054-432e-8cd5-4a61302dfdba\" is the ID of the previous created temporary Test Object."
			+ "\n\n"
			+ "Example for starting a Test Run for a file-based Test, referencing Test data in the web:<br/>"
			+ "\n\n"
			+ "    {\n"
			+ "        \"label\": \"Test run on 15:00 - 01.01.2017 with Conformance class INSPIRE Profile based on EN ISO 19115 and EN ISO 19119\",\n"
			+ "        \"executableTestSuiteIds\": [\"EIDec7323d5-d8f0-4cfe-b23a-b826df86d58c\"],\n"
			+ "        \"arguments\": {\n"
			+ "            \"files_to_test\": \".*\",\n"
			+ "            \"tests_to_execute\": \".*\"\n"
			+ "        },\n"
			+ "        \"testObject\": {\n"
			+ "            \"resources\": {\n"
			+ "                \"data\": \"http://example.com/test-data.xml\"\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "\n\n"
			, tags = {TEST_RUNS_TAG_NAME})
	@ApiResponses(value = {
			@ApiResponse(code = 201, message = "Test Run created"),
			@ApiResponse(code = 400, message = "Invalid request", response = RestExceptionHandler.ApiError.class),
			@ApiResponse(code = 404, message = "Test Object or Executable Test Suite with ID not found", response = RestExceptionHandler.ApiError.class),
			@ApiResponse(code = 409, message = "Test Object already in use", response = RestExceptionHandler.ApiError.class),
			@ApiResponse(code = 500, message = "Internal error", response = RestExceptionHandler.ApiError.class),
	})
	@RequestMapping(value = TEST_RUNS_URL, method = RequestMethod.POST)
	public void start(@RequestBody @Valid StartTestRunRequest testRunRequest, BindingResult result, HttpServletRequest request, HttpServletResponse response)
			throws LocalizableApiError {

		if(result.hasErrors()) {
			throw new LocalizableApiError(result.getFieldError());
		}

		// Remove finished test runs
		taskPoolRegistry.removeDone();

		try {
			final TestRunDto testRunDto = testRunRequest.toTestRun(testObjectController, testDriverController);

			final TestObjectDto tO = testRunDto.getTestObjects().get(0);

			// Check if test object is already in use
			// TODO check if Test Object is already in use
			for (TestRun tR : taskPoolRegistry.getTasks()) {
				if (!tR.getProgress().getState().isCompletedFailedCanceledOrFinalizing() &&
						tO.getId().equals(tR.getResult().getTestObjects().get(0).getId())) {
					logger.info("Rejecting test start: test object " + tO.getId() + " is in use");
					throw new LocalizableApiError("l.testObject.lock", false, 409, tO.getLabel());
				}
			}

			tO.setAuthor(request.getRemoteAddr());
			testObjectController.initResourcesAndAdd(tO);

			// this will save the Dto
			initAndSubmit(testRunDto);

			response.setStatus(HttpStatus.CREATED.value());
			streamingService.asJson2(testRunDao, request, response, testRunDto.getId().getId());
		} catch (URISyntaxException e) {
			throw new LocalizableApiError(e);
		} catch (ObjectWithIdNotFoundException e) {
			throw new LocalizableApiError(e);
		} catch (StorageException e) {
			throw new LocalizableApiError(e);
		} catch (IOException e) {
			throw new LocalizableApiError(e);
		}
	}

}
