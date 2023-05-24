package org.oagi.score.e2e.TS_20_NamespaceManagement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.oagi.score.e2e.BaseTest;
import org.oagi.score.e2e.obj.AppUserObject;

import java.util.ArrayList;
import java.util.List;

@Execution(ExecutionMode.CONCURRENT)
public class TC_20_1_DeveloperManagementOFNamespaces extends BaseTest{
    private List<AppUserObject> randomAccounts = new ArrayList<>();

    @BeforeEach
    public void init() {
        super.init();
    }

    @AfterEach
    public void tearDown() {
        super.tearDown();

        // Delete random accounts
        this.randomAccounts.forEach(randomAccount -> {
            getAPIFactory().getAppUserAPI().deleteAppUserByLoginId(randomAccount.getLoginId());
        });
    }

    private void thisAccountWillBeDeletedAfterTests(AppUserObject appUser) {
        this.randomAccounts.add(appUser);
    }

    @Test
    public void test_TA_20_1_1_a() {


    }

    @Test
    public void test_TA_20_1_1_b() {

    }


    @Test
    public void test_TA_20_1_1_c() {

    }

    @Test
    public void test_TA_20_1_1_d() {

    }

    @Test
    public void test_TA_20_1_2() {

    }

    @Test
    public void test_TA_20_1_3() {

    }

    @Test
    public void test_TA_20_1_4() {

    }

    @Test
    public void test_TA_20_1_5() {

    }

    @Test
    public void test_TA_20_1_6() {

    }
}
