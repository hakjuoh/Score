package org.oagi.score.e2e.TS_15_ReleaseBranchCoreComponentManagementBehaviorForEndUser.acc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.oagi.score.e2e.BaseTest;
import org.oagi.score.e2e.api.CoreComponentAPI;
import org.oagi.score.e2e.obj.*;
import org.oagi.score.e2e.page.HomePage;
import org.oagi.score.e2e.page.core_component.ACCSetBaseACCDialog;
import org.oagi.score.e2e.page.core_component.ACCViewEditPage;
import org.oagi.score.e2e.page.core_component.ASCCPViewEditPage;
import org.oagi.score.e2e.page.core_component.ViewEditCoreComponentPage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.oagi.score.e2e.impl.PageHelper.getText;
import static org.oagi.score.e2e.impl.PageHelper.switchToMainTab;

@Execution(ExecutionMode.CONCURRENT)
public class TC_15_6_DeletingEndUserACC extends BaseTest {
    private final List<AppUserObject> randomAccounts = new ArrayList<>();

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
    public void test_TA_15_6_1() {
        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7.1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc = getAPIFactory().getCoreComponentAPI().createRandomACC(endUser, release, namespace, "WIP");
        ACCViewEditPage accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accViewEditPage.hitDeleteButton();

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        WebElement accNode = accViewEditPage.getNodeByPath("/" + acc.getDen());
        ACCViewEditPage.ACCPanel accPanel = accViewEditPage.getACCPanel(accNode);
        assertEquals("1", getText(accPanel.getRevisionField()));
        assertEquals("Deleted", getText(accPanel.getStateField()));
    }

    @Test
    public void test_TA_15_6_2() {
        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7.1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc, acc_descendant, acc_descendant_base, new_acc_base;

        {
            CoreComponentAPI coreComponentAPI = getAPIFactory().getCoreComponentAPI();

            acc = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            new_acc_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Production");

            acc_descendant = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            acc_descendant_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Deleted");
            coreComponentAPI.updateBasedACC(acc_descendant, acc_descendant_base);

            coreComponentAPI.updateBasedACC(acc, acc_descendant);
        }
        ACCViewEditPage accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        WebElement accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + acc_descendant.getDen());
        assertEquals(1, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc_descendant_base.getAccManifestId());
        accViewEditPage.hitRestoreButton();

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        assertEquals(0, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc_descendant.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc_descendant.getDen() + "/" + acc_descendant_base.getDen());
        accViewEditPage.deleteBaseACC("/" + acc_descendant.getDen() + "/" + acc_descendant_base.getDen());
        ACCSetBaseACCDialog accSetBaseACCDialog = accViewEditPage.setBaseACC("/" + acc_descendant.getDen());
        accSetBaseACCDialog.hitApplyButton(new_acc_base.getDen());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + acc_descendant.getDen() + "/" + new_acc_base.getDen());
        assertTrue(accNode.isDisplayed());
    }


    @Test
    public void test_TA_15_6_3() {

        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7.1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc, acc_descendant_base, new_acc_base, acc_base_for_delete;

        {
            CoreComponentAPI coreComponentAPI = getAPIFactory().getCoreComponentAPI();

            acc = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            acc_base_for_delete = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            new_acc_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Production");
            acc_descendant_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Deleted");
            coreComponentAPI.updateBasedACC(acc, acc_descendant_base);
        }
        ACCViewEditPage accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        WebElement accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + acc_descendant_base.getDen());
        assertEquals(1, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        accViewEditPage.deleteBaseACC("/" + acc.getDen() + "/" + acc_descendant_base.getDen());
        ACCSetBaseACCDialog accSetBaseACCDialog = accViewEditPage.setBaseACC("/" + acc.getDen());
        accSetBaseACCDialog.hitApplyButton(new_acc_base.getDen());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + new_acc_base.getDen());
        assertTrue(accNode.isDisplayed());
        accViewEditPage.deleteBaseACC("/" + acc.getDen() + "/" + new_acc_base.getDen());
        accSetBaseACCDialog = accViewEditPage.setBaseACC("/" + acc.getDen());
        accSetBaseACCDialog.hitApplyButton(acc_base_for_delete.getDen());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc_base_for_delete.getAccManifestId());
        accViewEditPage.hitDeleteButton();

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + acc_base_for_delete.getDen());
        assertEquals(1, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc_base_for_delete.getAccManifestId());
        accViewEditPage.hitRestoreButton();

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + acc_base_for_delete.getDen());
        assertEquals(0, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

    }

    @Test
    public void test_TA_15_6_4() {
        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7.1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc, acc_descendant_base, new_acc_base, acc_association;
        ASCCObject ascc;
        ASCCPObject asccp;

        {
            CoreComponentAPI coreComponentAPI = getAPIFactory().getCoreComponentAPI();

            acc = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            acc_association = coreComponentAPI.createRandomACC(endUser, release, namespace, "Deleted");
            new_acc_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Production");
            coreComponentAPI.updateBasedACC(acc, new_acc_base);

            asccp = coreComponentAPI.createRandomASCCP(acc_association, endUser, namespace, "WIP");
            ascc = coreComponentAPI.appendASCC(acc, asccp, "WIP");
            ascc.setCardinalityMax(1);
            coreComponentAPI.updateASCC(ascc);
        }
        ACCViewEditPage accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        WebElement accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + asccp.getPropertyTerm());
        assertEquals(1, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc_association.getAccManifestId());
        accViewEditPage.hitRestoreButton();

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc.getDen() + "/" + asccp.getPropertyTerm());

        assertEquals(0, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());
    }

    @Test
    public void test_TA_15_6_5() {
        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7,1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc, acc_descendant_base;

        {
            CoreComponentAPI coreComponentAPI = getAPIFactory().getCoreComponentAPI();

            acc = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            acc_descendant_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Deleted");
            coreComponentAPI.updateBasedACC(acc, acc_descendant_base);
        }
        ACCViewEditPage accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        WebElement accNode = accViewEditPage.getNodeByPath("/" + acc.getDen());
        assertEquals(1, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc_descendant_base.getAccManifestId());
        accViewEditPage.hitRestoreButton();

        viewEditCoreComponentPage.openPage();
        accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accNode = accViewEditPage.getNodeByPath("/" + acc.getDen());
        assertEquals(0, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

    }

    @Test
    public void test_TA_15_6_6() {
        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7.1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc, new_acc_base, acc_association;
        ASCCObject ascc;
        ASCCPObject asccp;

        {
            CoreComponentAPI coreComponentAPI = getAPIFactory().getCoreComponentAPI();

            acc = coreComponentAPI.createRandomACC(endUser, release, namespace, "WIP");
            acc_association = coreComponentAPI.createRandomACC(endUser, release, namespace, "Deleted");
            new_acc_base = coreComponentAPI.createRandomACC(endUser, release, namespace, "Production");
            coreComponentAPI.updateBasedACC(acc, new_acc_base);

            asccp = coreComponentAPI.createRandomASCCP(acc_association, endUser, namespace, "WIP");
            ascc = coreComponentAPI.appendASCC(acc, asccp, "WIP");
            ascc.setCardinalityMax(1);
            coreComponentAPI.updateASCC(ascc);
        }
        ASCCPViewEditPage asccpViewEditPage = viewEditCoreComponentPage.openASCCPViewEditPageByManifestID(asccp.getAsccpManifestId());
        WebElement accNode = asccpViewEditPage.getNodeByPath("/" + asccp.getPropertyTerm() + "/" + acc_association.getDen());
        assertEquals(1, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size());

        ACCViewEditPage accViewEditPage = asccpViewEditPage.openACCInNewTab(accNode);
        accViewEditPage.hitRestoreButton();

        switchToMainTab(getDriver());
        viewEditCoreComponentPage.openPage();
        asccpViewEditPage = viewEditCoreComponentPage.openASCCPViewEditPageByManifestID(asccp.getAsccpManifestId());
        WebElement asccpNode = asccpViewEditPage.getNodeByPath("/" + asccp.getPropertyTerm());
        assertEquals(0, getDriver().findElements(By.xpath("//*[@ng-reflect-message=\"Deleted\" or contains(@class,'text-line-through')]")).size())

    }

    @Test
    public void test_TA_15_6_7() {
        AppUserObject endUser = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(endUser);

        String branch = "10.8.7.1";
        HomePage homePage = loginPage().signIn(endUser.getLoginId(), endUser.getPassword());
        ViewEditCoreComponentPage viewEditCoreComponentPage =
                homePage.getCoreComponentMenu().openViewEditCoreComponentSubMenu();

        ReleaseObject release = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(branch);
        NamespaceObject namespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(endUser);
        ACCObject acc = getAPIFactory().getCoreComponentAPI().createRandomACC(endUser, release, namespace, "Production");
        ACCViewEditPage accViewEditPage = viewEditCoreComponentPage.openACCViewEditPageByManifestID(acc.getAccManifestId());
        accViewEditPage.hitAmendButton();
        WebElement accNode = accViewEditPage.getNodeByPath("/" + acc.getDen());
        ACCViewEditPage.ACCPanel accPanel = accViewEditPage.getACCPanel(accNode);
        assertEquals("2", getText(accPanel.getRevisionField()));
        assertEquals("WIP", getText(accPanel.getStateField()));
        assertEquals(0, getDriver().findElements(By.xpath("//span[contains(text(),\"Delete\")]//ancestor::button[1]")).size());

        accViewEditPage.moveToQA();
        assertEquals("2", getText(accPanel.getRevisionField()));
        assertEquals("QA", getText(accPanel.getStateField()));
        assertEquals(0, getDriver().findElements(By.xpath("//span[contains(text(),\"Delete\")]//ancestor::button[1]")).size());

        accViewEditPage.moveToProduction();
        assertEquals("2", getText(accPanel.getRevisionField()));
        assertEquals("Production", getText(accPanel.getStateField()));
        assertEquals(0, getDriver().findElements(By.xpath("//span[contains(text(),\"Delete\")]//ancestor::button[1]")).size());
    }


}
