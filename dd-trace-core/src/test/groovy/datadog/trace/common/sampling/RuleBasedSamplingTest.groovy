package datadog.trace.common.sampling

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP

class RuleBasedSamplingTest extends DDCoreSpecification {
  def "Rule Based Sampler is not created when properties not set"() {
    when:
    Sampler sampler = Sampler.Builder.forConfig(new Properties())

    then:
    !(sampler instanceof RuleBasedSampler)
  }

  def "Rule Based Sampler is not created when just rate limit set"() {
    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_RATE_LIMIT, "50")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    then:
    !(sampler instanceof RuleBasedSampler)
  }

  def "sampling config combinations"() {
    given:
    Properties properties = new Properties()
    if (serviceRules != null) {
      properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, serviceRules)
    }

    if (operationRules != null) {
      properties.setProperty(TRACE_SAMPLING_OPERATION_RULES, operationRules)
    }

    if (defaultRate != null) {
      properties.setProperty(TRACE_SAMPLE_RATE, defaultRate)
    }

    if (rateLimit != null) {
      properties.setProperty(TRACE_RATE_LIMIT, rateLimit)
    }
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)

    then:
    sampler instanceof PrioritySampler

    when:
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    then:
    span.getTag(RuleBasedSampler.SAMPLING_RULE_RATE) == expectedRuleRate
    span.getTag(RuleBasedSampler.SAMPLING_LIMIT_RATE) == expectedRateLimit
    span.getTag(RateByServiceSampler.SAMPLING_AGENT_RATE) == expectedAgentRate
    span.getSamplingPriority() == expectedPriority

    cleanup:
    tracer.close()

    where:
    serviceRules      | operationRules      | defaultRate | rateLimit | expectedRuleRate | expectedRateLimit | expectedAgentRate | expectedPriority
    // Matching neither passes through to rate based sampler
    "xx:1"            | null                | null        | "50"      | null             | null              | 1.0               | SAMPLER_KEEP
    null              | "xx:1"              | null        | "50"      | null             | null              | 1.0               | SAMPLER_KEEP

    // Matching neither with default rate
    null              | null                | "1"         | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | null                | "0"         | "50"      | 0                | null              | null              | USER_DROP
    "xx:1"            | null                | "1"         | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | "xx:1"              | "1"         | "50"      | 1.0              | 50                | null              | USER_KEEP
    "xx:1"            | null                | "0"         | "50"      | 0                | null              | null              | USER_DROP
    null              | "xx:1"              | "0"         | "50"      | 0                | null              | null              | USER_DROP

    // Matching service: keep
    "service:1"       | null                | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    "s.*:1"           | null                | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    ".*e:1"           | null                | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    "[a-z]+:1"        | null                | null        | "50"      | 1.0              | 50                | null              | USER_KEEP

    // Matching service: drop
    "service:0"       | null                | null        | "50"      | 0                | null              | null              | USER_DROP
    "s.*:0"           | null                | null        | "50"      | 0                | null              | null              | USER_DROP
    ".*e:0"           | null                | null        | "50"      | 0                | null              | null              | USER_DROP
    "[a-z]+:0"        | null                | null        | "50"      | 0                | null              | null              | USER_DROP

    // Matching service overrides default rate
    "service:1"       | null                | "0"         | "50"      | 1.0              | 50                | null              | USER_KEEP
    "service:0"       | null                | "1"         | "50"      | 0                | null              | null              | USER_DROP

    // multiple services
    "xxx:0,service:1" | null                | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    "xxx:1,service:0" | null                | null        | "50"      | 0                | null              | null              | USER_DROP

    // Matching operation : keep
    null              | "operation:1"       | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | "o.*:1"             | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | ".*n:1"             | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | "[a-z]+:1"          | null        | "50"      | 1.0              | 50                | null              | USER_KEEP

    // Matching operation: drop
    null              | "operation:0"       | null        | "50"      | 0                | null              | null              | USER_DROP
    null              | "o.*:0"             | null        | "50"      | 0                | null              | null              | USER_DROP
    null              | ".*n:0"             | null        | "50"      | 0                | null              | null              | USER_DROP
    null              | "[a-z]+:0"          | null        | "50"      | 0                | null              | null              | USER_DROP

    // Matching operation overrides default rate
    null              | "operation:1"       | "0"         | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | "operation:0"       | "1"         | "50"      | 0                | null              | null              | USER_DROP

    // multiple operation combinations
    null              | "xxx:0,operation:1" | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    null              | "xxx:1,operation:0" | null        | "50"      | 0                | null              | null              | USER_DROP

    // Service and operation name combinations
    "service:1"       | "operation:0"       | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    "service:1"       | "xxx:0"             | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    "service:0"       | "operation:1"       | null        | "50"      | 0                | null              | null              | USER_DROP
    "service:0"       | "xxx:1"             | null        | "50"      | 0                | null              | null              | USER_DROP
    "xxx:0"           | "operation:1"       | null        | "50"      | 1.0              | 50                | null              | USER_KEEP
    "xxx:1"           | "operation:0"       | null        | "50"      | 0                | null              | null              | USER_DROP

    // There are no tests for ordering within service or operation rules because the rule order in that case is unspecified
  }

  def "Rate limit is set for rate limited spans"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:1")
    properties.setProperty(TRACE_RATE_LIMIT, "1")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    DDSpan span2 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    ((PrioritySampler) sampler).setSamplingPriority(span1)
    // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
    ((PrioritySampler) sampler).setSamplingPriority(span2)

    then:
    span1.getTag(RuleBasedSampler.SAMPLING_RULE_RATE) == 1.0
    span1.getTag(RuleBasedSampler.SAMPLING_LIMIT_RATE) == 1.0
    span1.getTag(RateByServiceSampler.SAMPLING_AGENT_RATE) == null
    span1.getSamplingPriority() == USER_KEEP

    span2.getTag(RuleBasedSampler.SAMPLING_RULE_RATE) == 1.0
    span2.getTag(RuleBasedSampler.SAMPLING_LIMIT_RATE) == 1.0
    span2.getTag(RateByServiceSampler.SAMPLING_AGENT_RATE) == null
    span2.getSamplingPriority() == USER_DROP

    cleanup:
    tracer.close()
  }

  def "Rate limit is set for rate limited spans (matched on different rules)"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:1,foo:1")
    properties.setProperty(TRACE_RATE_LIMIT, "1")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    DDSpan span2 = tracer.buildSpan("operation")
      .withServiceName("foo")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    ((PrioritySampler) sampler).setSamplingPriority(span1)
    // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
    ((PrioritySampler) sampler).setSamplingPriority(span2)

    then:
    span1.getTag(RuleBasedSampler.SAMPLING_RULE_RATE) == 1.0
    span1.getTag(RuleBasedSampler.SAMPLING_LIMIT_RATE) == 1.0
    span1.getTag(RateByServiceSampler.SAMPLING_AGENT_RATE) == null
    span1.getSamplingPriority() == USER_KEEP

    span2.getTag(RuleBasedSampler.SAMPLING_RULE_RATE) == 1.0
    span2.getTag(RuleBasedSampler.SAMPLING_LIMIT_RATE) == 1.0
    span2.getTag(RateByServiceSampler.SAMPLING_AGENT_RATE) == null
    span2.getSamplingPriority() == USER_DROP

    cleanup:
    tracer.close()
  }
}
