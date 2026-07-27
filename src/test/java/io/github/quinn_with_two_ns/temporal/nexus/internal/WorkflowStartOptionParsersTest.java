package io.github.quinn_with_two_ns.temporal.nexus.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.time.Duration;
import org.junit.Test;

public class WorkflowStartOptionParsersTest {
  @Test
  public void parsesPositiveDurations() {
    assertEquals(
        Duration.ofSeconds(30), WorkflowStartOptionParsers.positiveDuration("attr", "PT30S"));
    assertEquals(Duration.ofHours(2), WorkflowStartOptionParsers.positiveDuration("attr", "PT2H"));

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.positiveDuration("attr", "PT0S");
          }
        },
        "attr must be greater than zero");
    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.positiveDuration("attr", "PT-5S");
          }
        },
        "attr must be greater than zero");
    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.positiveDuration("attr", "tomorrow");
          }
        },
        "attr must be an ISO-8601 duration");
  }

  @Test
  public void parsesNonNegativeDurations() {
    assertEquals(Duration.ZERO, WorkflowStartOptionParsers.nonNegativeDuration("attr", "PT0S"));
    assertEquals(
        Duration.ofMinutes(5), WorkflowStartOptionParsers.nonNegativeDuration("attr", "PT5M"));

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.nonNegativeDuration("attr", "PT-1S");
          }
        },
        "attr must not be negative");
  }

  @Test
  public void parsesNonNegativeIntegers() {
    assertEquals(0, WorkflowStartOptionParsers.nonNegativeInteger("attr", "0"));
    assertEquals(42, WorkflowStartOptionParsers.nonNegativeInteger("attr", "42"));

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.nonNegativeInteger("attr", "-1");
          }
        },
        "attr must not be negative");
    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.nonNegativeInteger("attr", "3.5");
          }
        },
        "attr must be an integer");
  }

  @Test
  public void parsesBackoffCoefficients() {
    assertEquals(1.0, WorkflowStartOptionParsers.backoffCoefficient("attr", "1.0"), 0.0);
    assertEquals(2.5, WorkflowStartOptionParsers.backoffCoefficient("attr", "2.5"), 0.0);

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.backoffCoefficient("attr", "0.9");
          }
        },
        "attr must be a finite number greater than or equal to 1.0");
    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.backoffCoefficient("attr", "Infinity");
          }
        },
        "attr must be a finite number greater than or equal to 1.0");
    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.backoffCoefficient("attr", "fast");
          }
        },
        "attr must be a number");
  }

  @Test
  public void parsesNonNegativeFloats() {
    assertEquals(0.0f, WorkflowStartOptionParsers.nonNegativeFloat("attr", "0"), 0.0f);
    assertEquals(1.5f, WorkflowStartOptionParsers.nonNegativeFloat("attr", "1.5"), 0.0f);

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.nonNegativeFloat("attr", "-0.5");
          }
        },
        "attr must be a finite, non-negative number");
    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.nonNegativeFloat("attr", "NaN");
          }
        },
        "attr must be a finite, non-negative number");
  }

  @Test
  public void parsesWorkflowIdReusePolicyNames() {
    assertEquals(
        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE,
        WorkflowStartOptionParsers.workflowIdReusePolicy("attr", "REJECT_DUPLICATE"));
    assertEquals(
        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE,
        WorkflowStartOptionParsers.workflowIdReusePolicy("attr", " allow-duplicate "));
    assertEquals(
        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING,
        WorkflowStartOptionParsers.workflowIdReusePolicy(
            "attr", "WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING"));

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.workflowIdReusePolicy("attr", "bogus");
          }
        },
        "attr is not a recognized workflow ID reuse policy");
  }

  @Test
  public void parsesWorkflowIdConflictPolicyNames() {
    assertEquals(
        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING,
        WorkflowStartOptionParsers.workflowIdConflictPolicy("attr", "use-existing"));
    assertEquals(
        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL,
        WorkflowStartOptionParsers.workflowIdConflictPolicy(
            "attr", "WORKFLOW_ID_CONFLICT_POLICY_FAIL"));

    assertParseFailure(
        new Parse() {
          @Override
          public void run() {
            WorkflowStartOptionParsers.workflowIdConflictPolicy("attr", "bogus");
          }
        },
        "attr is not a recognized workflow ID conflict policy");
  }

  private interface Parse {
    void run();
  }

  private static void assertParseFailure(Parse parse, String expected) {
    try {
      parse.run();
      fail("Expected parsing to fail with: " + expected);
    } catch (IllegalArgumentException e) {
      String message = String.valueOf(e.getMessage());
      assertTrue(
          "Expected <" + message + "> to contain <" + expected + ">", message.contains(expected));
    }
  }
}
