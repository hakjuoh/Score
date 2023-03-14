package org.oagi.score.e2e.TS_11_WorkingBranchCodeListManagementForDeveloper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.oagi.score.e2e.BaseTest;
import org.oagi.score.e2e.obj.*;
import org.oagi.score.e2e.page.HomePage;
import org.oagi.score.e2e.page.code_list.AddCodeListCommentDialog;
import org.oagi.score.e2e.page.code_list.EditCodeListPage;
import org.oagi.score.e2e.page.code_list.EditCodeListValueDialog;
import org.oagi.score.e2e.page.code_list.ViewEditCodeListPage;
import org.openqa.selenium.Keys;
import org.openqa.selenium.interactions.Actions;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.oagi.score.e2e.AssertionHelper.assertDisabled;
import static org.oagi.score.e2e.impl.PageHelper.invisibilityOfLoadingContainerElement;

@Execution(ExecutionMode.CONCURRENT)
public class TC_11_1_CodeListAccess extends BaseTest {
    private final List<AppUserObject> randomAccounts = new ArrayList<>();

    @BeforeEach
    public void init() {
        super.init();

    }

    private void thisAccountWillBeDeletedAfterTests(AppUserObject appUser) {
        this.randomAccounts.add(appUser);
    }

    @Test
    @DisplayName("TC_11_1_TA_1")
    public void test_TA_1() {
        ArrayList<CodeListObject> codeListForTesting = new ArrayList<>();
        AppUserObject developerB;
        ReleaseObject workingBranch;
        {
            developerB = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerB);

            AppUserObject developerA = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerA);
            NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomDeveloperNamespace(developerA);
            /**
             * Create Code List for Working branch. States - WIP, Draft and Candidate
             */
            workingBranch = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber("Working");
            CodeListObject codeListWIP = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "WIP");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListWIP, developerA);
            codeListForTesting.add(codeListWIP);

            CodeListObject codeListDraft = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Draft");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListDraft, developerA);
            codeListForTesting.add(codeListDraft);

            CodeListObject codeListCandidate = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Candidate");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListCandidate, developerA);
            codeListForTesting.add(codeListCandidate);
        }
        HomePage homePage = loginPage().signIn(developerB.getLoginId(), developerB.getPassword());
        ViewEditCodeListPage viewEditCodeListPage = homePage.getCoreComponentMenu().openViewEditCodeListSubMenu();
        getDriver().manage().window().maximize();
        for (CodeListObject cl : codeListForTesting) {
            assertNotEquals(developerB.getAppUserId(), cl.getOwnerUserId());
            viewEditCodeListPage.searchCodeListByNameAndBranch(cl.getName(), workingBranch.getReleaseNumber());
        }

    }

    @Test
    @DisplayName("TC_11_1_TA_2")
    public void test_TA_2() {
        ArrayList<CodeListObject> codeListForTesting = new ArrayList<>();
        AppUserObject developerA;
        ReleaseObject workingBranch;
        {
            developerA = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerA);
            NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomDeveloperNamespace(developerA);
            /**
             * Create Code List for Working branch. States - WIP, Draft and Candidate
             */
            workingBranch = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber("Working");
            CodeListObject codeListWIP = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "WIP");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListWIP, developerA);
            codeListForTesting.add(codeListWIP);

            CodeListObject codeListDraft = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Draft");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListDraft, developerA);
            codeListForTesting.add(codeListDraft);

            CodeListObject codeListCandidate = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Candidate");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListCandidate, developerA);
            codeListForTesting.add(codeListCandidate);
        }
        HomePage homePage = loginPage().signIn(developerA.getLoginId(), developerA.getPassword());
        getDriver().manage().window().maximize();
        for (CodeListObject cl : codeListForTesting) {
            assertEquals(developerA.getAppUserId(), cl.getOwnerUserId());
            assertEquals(Boolean.valueOf("true"), developerA.isDeveloper());
            ViewEditCodeListPage viewEditCodeListPage = homePage.getCoreComponentMenu().openViewEditCodeListSubMenu();
            EditCodeListPage editCodeListPage = viewEditCodeListPage.openCodeListViewEditPageByNameAndBranch(cl.getName(), workingBranch.getReleaseNumber());
            if (cl.getState().equals("WIP")) {
                /**
                 * The developer can view and edit the details (including code values) of a CL that is in the WIP state and owned by him.
                 */
                editCodeListPage.setDefinition("test definition");
                editCodeListPage.setDefinitionSource("test definition source");
                EditCodeListValueDialog editCodeListValueDialog = editCodeListPage.addCodeListValue();
                editCodeListValueDialog.setCode("code");
                editCodeListValueDialog.setMeaning("meaning");
                editCodeListValueDialog.hitAddButton();
                editCodeListPage.hitUpdateButton();
            } else {
                assertDisabled(editCodeListPage.getDefinitionField());
                assertDisabled(editCodeListPage.getDefinitionSourceField());
            }
        }

    }

    @Test
    @DisplayName("TC_11_1_TA_3")
    public void test_TA_3() {
        ArrayList<CodeListObject> codeListForTesting = new ArrayList<>();
        AppUserObject developerB;
        ReleaseObject workingBranch;
        {
            developerB = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerB);
            AppUserObject developerA = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerA);
            NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomDeveloperNamespace(developerA);
            /**
             * Create Code List for Working branch. States - WIP
             */
            workingBranch = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber("Working");
            CodeListObject codeListWIP = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "WIP");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListWIP, developerA);
            codeListForTesting.add(codeListWIP);
        }
        HomePage homePage = loginPage().signIn(developerB.getLoginId(), developerB.getPassword());
        getDriver().manage().window().maximize();
        for (CodeListObject cl : codeListForTesting) {
            assertNotEquals(developerB.getAppUserId(), cl.getOwnerUserId());
            assertEquals("WIP", cl.getState());
            ViewEditCodeListPage viewEditCodeListPage = homePage.getCoreComponentMenu().openViewEditCodeListSubMenu();
            /**
             * The developer CAN view but CANNOT edit the details of a CL that is in WIP state and owned by another developer.
             */
            EditCodeListPage editCodeListPage = viewEditCodeListPage.openCodeListViewEditPageByNameAndBranch(cl.getName(), workingBranch.getReleaseNumber());
            assertDisabled(editCodeListPage.getDefinitionField());
            assertDisabled(editCodeListPage.getDefinitionSourceField());
        }

    }
    @Test
    @DisplayName("TC_11_1_TA_4")
    public void test_TA_4() {
        ArrayList<CodeListObject> codeListForTesting = new ArrayList<>();
        AppUserObject developerB;
        ReleaseObject workingBranch;
        {
            developerB = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerB);
            AppUserObject developerA = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
            thisAccountWillBeDeletedAfterTests(developerA);
            NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomDeveloperNamespace(developerA);
            /**
             * Create Code List for Working branch. States - Draft, Candidate, Deleted, Release Draft
             */
            workingBranch = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber("Working");
            CodeListObject codeListDraft = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Draft");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListDraft, developerA);
            codeListForTesting.add(codeListDraft);

            CodeListObject codeListCandidate = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Candidate");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListCandidate, developerA);
            codeListForTesting.add(codeListCandidate);

            CodeListObject codeListDeleted = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Deleted");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListDeleted, developerA);
            codeListForTesting.add(codeListDeleted);

            /**
             * This code list should be in a draft release, not in a working branch
             */
            CodeListObject codeListReleaseDraft = getAPIFactory().getCodeListAPI().createRandomCodeList(developerA, namespace, workingBranch, "Release Draft");
            getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeListReleaseDraft, developerA);
            codeListForTesting.add(codeListReleaseDraft);
        }
        HomePage homePage = loginPage().signIn(developerB.getLoginId(), developerB.getPassword());
        getDriver().manage().window().maximize();
        for (CodeListObject cl : codeListForTesting) {
            /**
             * The developer can view the details of a CL that is in Draft, Candidate, Deleted, or Release Draft state and owned by any developer
             * but he cannot make any change except adding comments.
             */
            assertNotEquals(developerB.getAppUserId(), cl.getOwnerUserId());
            ArrayList<String> acceptedStates = new ArrayList<>(List.of("Draft", "Candidate", "Deleted", "Release Draft"));
            assertTrue(acceptedStates.contains(cl.getState()));
            ViewEditCodeListPage viewEditCodeListPage = homePage.getCoreComponentMenu().openViewEditCodeListSubMenu();
            EditCodeListPage editCodeListPage = viewEditCodeListPage.openCodeListViewEditPageByNameAndBranch(cl.getName(), workingBranch.getReleaseNumber());
            assertDisabled(editCodeListPage.getDefinitionField());
            assertDisabled(editCodeListPage.getDefinitionSourceField());
            AddCodeListCommentDialog addCommentDialog = editCodeListPage.hitAddCommentButton();
            addCommentDialog.setComment("test comment");
            pressEscape();
        }

    }

    private void pressEscape(){
        invisibilityOfLoadingContainerElement(getDriver());
        Actions action = new Actions(getDriver());
        action.sendKeys(Keys.ESCAPE).build().perform();
    }

    @AfterEach
    public void tearDown() {
        super.tearDown();
        // Delete random accounts
        this.randomAccounts.forEach(newUser -> {
            getAPIFactory().getAppUserAPI().deleteAppUserByLoginId(newUser.getLoginId());
        });
    }
}
