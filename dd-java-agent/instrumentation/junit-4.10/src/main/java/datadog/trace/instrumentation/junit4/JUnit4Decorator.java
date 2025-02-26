package datadog.trace.instrumentation.junit4;

import datadog.trace.api.DisableTestTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import datadog.trace.util.Strings;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class JUnit4Decorator extends TestDecorator {
  public static final JUnit4Decorator DECORATE = new JUnit4Decorator();

  @Override
  public String testFramework() {
    return "junit4";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-4"};
  }

  @Override
  public String component() {
    return "junit";
  }

  public boolean skipTrace(final Description description) {
    return description.getAnnotation(DisableTestTrace.class) != null
        || (description.getTestClass() != null
            && description.getTestClass().getAnnotation(DisableTestTrace.class) != null);
  }

  public void onTestStart(final AgentSpan span, final Description description) {
    onTestStart(span, description, null);
  }

  public void onTestStart(
      final AgentSpan span, final Description description, final String testNameArg) {
    final String testSuite = description.getClassName();
    final String fullTestName = (testNameArg != null) ? testNameArg : description.getMethodName();

    // For parameterized tests, the test name contains a custom test name
    // within the brackets. e.g. parameterized_test[0].
    // For the test.name tag, we need to normalize the test names.
    // "parameterized_test[0]" must be "parameterized_test".
    final String normalizedTestName = JUnit4Utils.normalizeTestName(fullTestName);
    span.setResourceName(testSuite + "." + normalizedTestName);
    span.setTag(Tags.TEST_SUITE, testSuite);
    span.setTag(Tags.TEST_NAME, normalizedTestName);

    // If the fullTestName != normalizedTestName, we assume it is a parameterized test.
    if (!fullTestName.equals(normalizedTestName)) {
      // No public access to the test parameters map in JUnit4.
      // In this case, we store the fullTestName in the "metadata.test_name" object.
      span.setTag(
          Tags.TEST_PARAMETERS,
          "{\"metadata\":{\"test_name\":\"" + Strings.escapeToJson(fullTestName) + "\"}}");
    }

    // We cannot set TEST_PASS status in onTestFinish(...) method because that method
    // is executed always after onTestFailure. For that reason, TEST_PASS status is preset
    // in onTestStart.
    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFinish(final AgentSpan span) {}

  public void onTestFailure(final AgentSpan span, final Failure failure) {
    final Throwable throwable = failure.getException();
    if (throwable != null) {
      span.setError(true);
      span.addThrowable(throwable);
      span.setTag(Tags.TEST_STATUS, TEST_FAIL);
    }
  }

  public void onTestAssumptionFailure(final AgentSpan span, final Failure failure) {
    // The consensus is to treat "assumptions failure" as skipped tests.
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    final Throwable throwable = failure.getException();
    if (throwable != null) {
      span.setTag(Tags.TEST_SKIP_REASON, throwable.getMessage());
    }
  }

  public void onTestIgnored(
      final AgentSpan span,
      final Description description,
      final String testName,
      final String reason) {
    onTestStart(span, description, testName);
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);
  }
}
