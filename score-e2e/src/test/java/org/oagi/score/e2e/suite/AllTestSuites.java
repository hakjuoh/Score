package org.oagi.score.e2e.suite;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SuiteDisplayName("All Test Suites")
@SelectClasses({
        TS_1.class,
        TS_2.class,
        TS_3.class,
        TS_4.class,
        TS_5.class,
        TS_6.class,
        TS_7.class,
        TS_9.class,
        TS_27.class
})
public class AllTestSuites {
}