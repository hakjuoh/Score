package org.oagi.score.e2e.TS_29_BIEUplifting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.oagi.score.e2e.BaseTest;
import org.oagi.score.e2e.api.CoreComponentAPI;
import org.oagi.score.e2e.menu.BIEMenu;
import org.oagi.score.e2e.obj.*;
import org.oagi.score.e2e.page.HomePage;
import org.oagi.score.e2e.page.bie.*;
import org.oagi.score.e2e.page.code_list.EditCodeListPage;
import org.oagi.score.e2e.page.code_list.ViewEditCodeListPage;
import org.oagi.score.e2e.page.core_component.ACCExtensionViewEditPage;
import org.oagi.score.e2e.page.core_component.SelectAssociationDialog;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.math.BigInteger;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.oagi.score.e2e.impl.PageHelper.*;

@Execution(ExecutionMode.SAME_THREAD)
public class TC_29_1_BIEUplifting extends BaseTest {
    private List<AppUserObject> randomAccounts = new ArrayList<>();
    String prev_release = "10.8.6";
    String curr_release = "10.8.8";
    AppUserObject usera, userb, developer;
    boolean precondition_created = false;
    Map<String, TopLevelASBIEPObject> testingBIEs = new HashMap<>();

    @BeforeEach
    public void init() {
        super.init();
        if (!precondition_created){
            preconditions();
        }
        precondition_created = true;
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

    public void preconditions(){
        usera = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(usera);

        userb = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(userb);

        developer = getAPIFactory().getAppUserAPI().createRandomDeveloperAccount(false);
        thisAccountWillBeDeletedAfterTests(developer);

        BusinessContextObject context = getAPIFactory().getBusinessContextAPI().createRandomBusinessContext(usera);
        NamespaceObject euNamespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(usera);


        HomePage homePage = loginPage().signIn(usera.getLoginId(), usera.getPassword());

        BIEMenu bieMenu = homePage.getBIEMenu();
        ViewEditBIEPage viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        CreateBIEForSelectBusinessContextsPage createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        CreateBIEForSelectTopLevelConceptPage createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        EditBIEPage editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("Enterprise Unit. Enterprise Unit", prev_release);
        EditBIEPage.TopLevelASBIEPPanel topLevelASBIEPPanel = editBIEPage.getTopLevelASBIEPPanel();
        String currentUrl = getDriver().getCurrentUrl();
        BigInteger topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        TopLevelASBIEPObject topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIE1QA")){
            testingBIEs.put("BIE1QA", topLevelASBIEP);
        }else{
            testingBIEs.put("BIE1QA", topLevelASBIEP);
        }

        topLevelASBIEPPanel.setBusinessTerm("aBusinessTerm");
        topLevelASBIEPPanel.setRemark("aRemark");
        topLevelASBIEPPanel.setStatus("aStatus");
        editBIEPage.hitUpdateButton();

        ACCExtensionViewEditPage accExtensionViewEditPage =
                editBIEPage.extendBIELocallyOnNode("/Enterprise Unit/Extension");
        SelectAssociationDialog selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Product Classification. Classification");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Incorporation Location. Location");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Code List. Code List");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Revised Item Status. Status");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Usage Description. Text");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Last Modification Date Time. Date Time");

        accExtensionViewEditPage.setNamespace(euNamespace);
        accExtensionViewEditPage.hitUpdateButton();
        accExtensionViewEditPage.moveToQA();
        accExtensionViewEditPage.moveToProduction();

        viewEditBIEPage.openPage();
        viewEditBIEPage.setBranch(prev_release);
        TopLevelASBIEPObject BIE1QA = testingBIEs.get("BIE1QA");
        editBIEPage = viewEditBIEPage.openEditBIEPage(BIE1QA);
        WebElement bbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Last Modification Date Time");
        EditBIEPage.BBIEPanel bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Identifier");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Name");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();

        bbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Identifier Set/Scheme Version Identifier");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");

        bbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Incorporation Location/CAGEID");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setValueConstraint("Fixed Value");
        bbiePanel.setFixedValue("99");
        bbiePanel.setValueDomain("language");
        bbiePanel.setContextDefinition("defcon");

        WebElement asbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Code List/Code List Value");
        EditBIEPage.ASBIEPanel asbiePanel = editBIEPage.getASBIEPanel(asbieNode);
        asbiePanel.toggleUsed();
        asbiePanel.setRemark("aRemark");
        asbiePanel.setContextDefinition("defcon");

        WebElement bbieSCNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Incorporation Location/Physical Address/Status/Identifier/Scheme Agency Identifier");
        EditBIEPage.BBIESCPanel bbiescPanel = editBIEPage.getBBIESCPanel(bbieSCNode);
        bbiescPanel.toggleUsed();
        bbiescPanel.setRemark("aRemark");
        bbiescPanel.setExample("anExample");
        bbiescPanel.setValueConstraint("Fixed Value");
        bbiescPanel.setFixedValue("99");
        bbiescPanel.setValueDomain("normalized string");
        bbiescPanel.setContextDefinition("defcon");

        bbieSCNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Product Classification/Extension/Indicator/Type Code");
        bbiescPanel = editBIEPage.getBBIESCPanel(bbieSCNode);
        bbiescPanel.toggleUsed();
        bbiescPanel.setRemark("aRemark");
        bbiescPanel.setExample("anExample");
        bbiescPanel.setValueConstraint("Fixed Value");
        bbiescPanel.setFixedValue("99");
        bbiescPanel.setValueDomain("any URI");
        bbiescPanel.setContextDefinition("defcon");

        asbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Revised Item Status");
        asbiePanel = editBIEPage.getASBIEPanel(asbieNode);
        asbiePanel.toggleUsed();
        asbiePanel.setRemark("aRemark");
        asbiePanel.setContextDefinition("defcon");
        asbiePanel.setCardinalityMin(11);
        asbiePanel.setCardinalityMax(99);

        bbieNode = editBIEPage.getNodeByPath("/Enterprise Unit/Extension/Revised Item Status/Reason Code");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setContextDefinition("defcon");
        bbiePanel.setValueConstraint("Default Value");
        bbiePanel.setDefaultValue("99");
        bbiePanel.setValueDomain("any URI");

        editBIEPage.hitUpdateButton();
        editBIEPage.moveToQA();

        //BIEuserbProduction previousRelease
        bieMenu = homePage.getBIEMenu();
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("Change Acknowledge Shipment Status. Change Acknowledge Shipment Status", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIEUserbProduction")){
            testingBIEs.put("BIEUserbProduction", topLevelASBIEP);
        }else{
            testingBIEs.put("BIEUserbProduction", topLevelASBIEP);
        }

        bbieNode = editBIEPage.getNodeByPath("/Change Acknowledge Shipment Status/System Environment Code");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setContextDefinition("defcon");
        bbiePanel.setValueConstraint("Fixed Value");
        bbiePanel.setFixedValue("99");
        bbiePanel.setValueDomainRestriction("Code");
        bbiePanel.setValueDomain("oacl_SystemEnvironmentCode");
        editBIEPage.hitUpdateButton();

        asbieNode = editBIEPage.getNodeByPath("/Change Acknowledge Shipment Status/Application Area");
        asbiePanel = editBIEPage.getASBIEPanel(asbieNode);
        asbiePanel.setRemark("aRemark");
        asbiePanel.setContextDefinition("defcon");
        editBIEPage.hitUpdateButton();

        bbieSCNode = editBIEPage.getNodeByPath("/Change Acknowledge Shipment Status/Application Area/Scenario Identifier/Scheme Version Identifier");
        bbiescPanel = editBIEPage.getBBIESCPanel(bbieSCNode);
        bbiescPanel.toggleUsed();
        bbiescPanel.setRemark("aRemark");
        bbiescPanel.setExample("anExample");
        bbiescPanel.setValueConstraint("Fixed Value");
        bbiescPanel.setFixedValue("99");
        bbiescPanel.setValueDomainRestriction("Agency");
        bbiescPanel.setValueDomain("clm63055D16B_AgencyIdentification");
        bbiescPanel.setContextDefinition("defcon");
        editBIEPage.hitUpdateButton();
        editBIEPage.moveToQA();
        editBIEPage.moveToProduction();

        homePage.logout();
        homePage = loginPage().signIn(userb.getLoginId(), userb.getPassword());
        //BIEuserbReusedChild previousRelease
        BusinessContextObject contextForUserb = getAPIFactory().getBusinessContextAPI().createRandomBusinessContext(userb);
        bieMenu = homePage.getBIEMenu();
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(contextForUserb));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("Unit Packaging. Packaging", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIEUserbReusedChild")){
            testingBIEs.put("BIEUserbReusedChild", topLevelASBIEP);
        }else{
            testingBIEs.put("BIEUserbReusedChild", topLevelASBIEP);
        }

        bbieNode = editBIEPage.getNodeByPath("/Unit Packaging/Capacity Per Package Quantity");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setContextDefinition("defcon");
        bbiePanel.setValueConstraint("Fixed Value");
        bbiePanel.setFixedValue("99");
        editBIEPage.hitUpdateButton();

        asbieNode = editBIEPage.getNodeByPath("/Unit Packaging/Dimensions");
        asbiePanel = editBIEPage.getASBIEPanel(asbieNode);
        asbiePanel.toggleUsed();
        asbiePanel.setCardinalityMax(99);
        asbiePanel.setCardinalityMin(11);
        asbiePanel.setRemark("aRemark");
        asbiePanel.setContextDefinition("defcon");
        editBIEPage.hitUpdateButton();

        bbieSCNode = editBIEPage.getNodeByPath("/Unit Packaging/UPC Packaging Level Code/List Agency Identifier");
        bbiescPanel = editBIEPage.getBBIESCPanel(bbieSCNode);
        bbiescPanel.toggleUsed();
        bbiescPanel.setRemark("aRemark");
        bbiescPanel.setExample("anExample");
        bbiescPanel.setValueConstraint("Fixed Value");
        bbiescPanel.setFixedValue("99");
        bbiescPanel.setValueDomain("token");
        bbiescPanel.setContextDefinition("defcon");
        editBIEPage.hitUpdateButton();
        editBIEPage.moveToQA();
        editBIEPage.moveToProduction();

        //BIEuserbReusedParent previousRelease

        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(contextForUserb));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("From UOM Package. UOM Package", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIEUserbReusedParent")){
            testingBIEs.put("BIEUserbReusedParent", topLevelASBIEP);
        }else{
            testingBIEs.put("BIEUserbReusedParent", topLevelASBIEP);
        }

        bbieNode = editBIEPage.getNodeByPath("/From UOM Package/UOM Code");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setContextDefinition("defcon");
        bbiePanel.setValueConstraint("Fixed Value");
        bbiePanel.setFixedValue("99");
        bbiePanel.setValueDomain("token");
        editBIEPage.hitUpdateButton();

        asbieNode = editBIEPage.getNodeByPath("/From UOM Package/Unit Packaging");
        asbiePanel = editBIEPage.getASBIEPanel(asbieNode);
        SelectProfileBIEToReuseDialog selectProfileBIEToReuseDialog = editBIEPage.reuseBIEOnNode("/From UOM Package/Unit Packaging");
        TopLevelASBIEPObject reusedBIE = testingBIEs.get("BIEUserbReusedChild");
        selectProfileBIEToReuseDialog.selectBIEToReuse(reusedBIE);
        editBIEPage.moveToQA();
        editBIEPage.moveToProduction();

        //BIEUserbReusedScenario previousRelease
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(contextForUserb));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("UOM Code Conversion Rate. UOM Code Conversion Rate", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIEUserbReusedScenario")){
            testingBIEs.put("BIEUserbReusedScenario", topLevelASBIEP);
        }else{
            testingBIEs.put("BBIEUserbReusedScenario", topLevelASBIEP);
        }

        selectProfileBIEToReuseDialog = editBIEPage.reuseBIEOnNode("/UOM Code Conversion Rate/From UOM Package");
        reusedBIE = testingBIEs.get("BIEUserbReusedParent");
        selectProfileBIEToReuseDialog.selectBIEToReuse(reusedBIE);
        editBIEPage.moveToQA();

        homePage.logout();
        homePage = loginPage().signIn(developer.getLoginId(), developer.getPassword());
        //BIECustomerItemIdentification previousRelease
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("Customer Item Identification. Item Identification", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIECustomerItemIdentification")){
            testingBIEs.put("BIECustomerItemIdentification", topLevelASBIEP);
        }else{
            testingBIEs.put("BIECustomerItemIdentification", topLevelASBIEP);
        }

        bbieNode = editBIEPage.getNodeByPath("/Customer Item Identification/Revision Identifier");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setContextDefinition("defcon");
        bbiePanel.setValueConstraint("Default Value");
        bbiePanel.setDefaultValue("99");
        editBIEPage.hitUpdateButton();
        editBIEPage.moveToQA();
        editBIEPage.moveToProduction();

        //BIEBOMItemData previousRelease
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("BOM Item Data. BOM Item Data", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIEBOMItemData")){
            testingBIEs.put("BIEBOMItemData", topLevelASBIEP);
        }else{
            testingBIEs.put("BBIEBOMItemData", topLevelASBIEP);
        }


        selectProfileBIEToReuseDialog = editBIEPage.reuseBIEOnNode("/BOM Item Data/Customer Item Identification");
        reusedBIE = testingBIEs.get("BIECustomerItemIdentification");
        selectProfileBIEToReuseDialog.selectBIEToReuse(reusedBIE);

        bbieNode = editBIEPage.getNodeByPath("/BOM Item Data/Reference Designator Identifier");
        bbiePanel = editBIEPage.getBBIEPanel(bbieNode);
        bbiePanel.toggleUsed();
        bbiePanel.setRemark("aRemark");
        bbiePanel.setExample("anExample");
        bbiePanel.setContextDefinition("defcon");
        bbiePanel.setValueConstraint("Default Value");
        bbiePanel.setDefaultValue("99");
        editBIEPage.hitUpdateButton();

        bbieSCNode = editBIEPage.getNodeByPath("/BOM Item Data/Supplier Item Identification/Item Identifier Set/Identifier/Scheme Version Identifier");
        bbiescPanel = editBIEPage.getBBIESCPanel(bbieSCNode);
        bbiescPanel.toggleUsed();
        bbiescPanel.setRemark("aRemark");
        bbiescPanel.setExample("anExample");
        bbiescPanel.setContextDefinition("defcon");
        assertEquals("1", getText(bbiescPanel.getCardinalityMaxField()));
        editBIEPage.hitUpdateButton();
        editBIEPage.moveToQA();
        editBIEPage.moveToProduction();

        //BIEBOMDoubleNested previousRelease
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("BOM. BOM", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIEBOMDoubleNested")){
            testingBIEs.put("BIEBOMDoubleNested", topLevelASBIEP);
        }else{
            testingBIEs.put("BIEBOMDoubleNested", topLevelASBIEP);
        }

        bbieSCNode = editBIEPage.getNodeByPath("/BOM/BOM Header/Document Identifier Set/Identifier/Scheme Agency Identifier");
        bbiescPanel = editBIEPage.getBBIESCPanel(bbieSCNode);
        bbiescPanel.toggleUsed();
        selectProfileBIEToReuseDialog = editBIEPage.reuseBIEOnNode("/BOM/BOM Item Data");
        reusedBIE = testingBIEs.get("BIEBOMItemData");
        selectProfileBIEToReuseDialog.selectBIEToReuse(reusedBIE);

        asbieNode = editBIEPage.getNodeByPath("/BOM/BOM Option/BOM Item Data/Hazardous Material");
        asbiePanel = editBIEPage.getASBIEPanel(asbieNode);
        asbiePanel.toggleUsed();
        editBIEPage.hitUpdateButton();

        homePage.logout();
        homePage = loginPage().signIn(usera.getLoginId(), usera.getPassword());
        List<String> euCLStates = new ArrayList<>();
        euCLStates.add("WIP");
        euCLStates.add("QA");
        euCLStates.add("Production");
        euCLStates.add("Deleted");
        ReleaseObject prev_releaseObject = getAPIFactory().getReleaseAPI().getReleaseByReleaseNumber(prev_release);
        RandomCodeListWithStateContainer euCodeListWithStateContainer = new RandomCodeListWithStateContainer(usera, prev_releaseObject, euNamespace, euCLStates);
        CodeListObject CLaccessUseraDeprecated = getAPIFactory().getCodeListAPI().createRandomCodeList(usera, euNamespace, prev_releaseObject, "Production");
        CodeListValueObject codeListValue = getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(CLaccessUseraDeprecated, usera);
        ViewEditCodeListPage viewEditCodeListPage = homePage.getCoreComponentMenu().openViewEditCodeListSubMenu();
        EditCodeListPage editCodeListPage = viewEditCodeListPage.openCodeListViewEditPageByNameAndBranch(CLaccessUseraDeprecated.getName(), prev_release);
        editCodeListPage.hitAmendButton();
        click(editCodeListPage.getDeprecatedSelectField());
        editCodeListPage.setDefinition("Check the Deprecated Checkbox");
        editCodeListPage.hitUpdateButton();
        editCodeListPage.moveToQA();
        editCodeListPage.moveToProduction();

        viewEditCodeListPage.openPage();
        editCodeListPage =  viewEditCodeListPage.openCodeListViewEditPageByNameAndBranch("oacl_MatchDocumentCode", prev_release);
        editCodeListPage.hitDeriveCodeListBasedOnThisButton();
        editCodeListPage.setName("CLuserderived_BIEUp");
        editCodeListPage.setNamespace(euNamespace);
        editCodeListPage.setDefinition("aDefinition");
        editCodeListPage.hitUpdateButton();

        //BIECAGUplift prev_release
        bieMenu = homePage.getBIEMenu();
        viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("Child Item Reference. Child Item Reference", prev_release);
        currentUrl = getDriver().getCurrentUrl();
        topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));
        topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        if (!testingBIEs.containsKey("BIECAGUplift")){
            testingBIEs.put("BIECAGUplift", topLevelASBIEP);
        }else{
            testingBIEs.put("BIECAGUplift", topLevelASBIEP);
        }

        accExtensionViewEditPage =
                editBIEPage.extendBIELocallyOnNode("/Child Item Reference/Extension");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Effectivity Relation Code. Code");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Validation Indicator. Indicator");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Method Consequence Text. Text");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Record Set Reference Identifier. Identifier");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Record Set Total Number. Number");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Latest Start Date Time. Date Time");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Request Language Code. Code");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Transport Temperature. Measure");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Correlation Identifier. Identifier");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Reason. Sequenced_ Open_ Text");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Child Item Reference User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Record Set Save Indicator. Indicator");

        accExtensionViewEditPage.setNamespace(euNamespace);
        accExtensionViewEditPage.hitUpdateButton();
        accExtensionViewEditPage.moveToQA();
        accExtensionViewEditPage.moveToProduction();
        homePage.logout();

    }

    private class RandomCodeListWithStateContainer {
        private final AppUserObject appUser;
        private List<String> states = new ArrayList<>();
        private final HashMap<String, CodeListObject> stateCodeLists = new HashMap<>();
        private final HashMap<String, CodeListValueObject> stateCodeListValues = new HashMap<>();

        public RandomCodeListWithStateContainer(AppUserObject appUser, ReleaseObject release, NamespaceObject namespace, List<String> states) {
            this.appUser = appUser;
            this.states = states;

            for (int i = 0; i < this.states.size(); ++i) {
                CodeListObject codeList;
                CodeListValueObject codeListValue;
                String state = this.states.get(i);
                {
                    codeList = getAPIFactory().getCodeListAPI().createRandomCodeList(this.appUser, namespace, release, state);
                    codeListValue = getAPIFactory().getCodeListValueAPI().createRandomCodeListValue(codeList, this.appUser);
                    stateCodeLists.put(state, codeList);
                    stateCodeListValues.put(state, codeListValue);
                }
            }
        }

    }

    @Test
    public void test_TA_29_1_1() {

        HomePage homePage = loginPage().signIn(usera.getLoginId(), usera.getPassword());
        BIEMenu bieMenu = homePage.getBIEMenu();
        UpliftBIEPage upliftBIEPage = bieMenu.openUpliftBIESubMenu();
        upliftBIEPage.setSourceBranch(curr_release);
        assertThrows(TimeoutException.class, () -> upliftBIEPage.setTargetBranch(prev_release));
    }

    @Test
    public void test_TA_29_1_2() {

        HomePage homePage = loginPage().signIn(userb.getLoginId(), userb.getPassword());
        BIEMenu bieMenu = homePage.getBIEMenu();
        UpliftBIEPage upliftBIEPage = bieMenu.openUpliftBIESubMenu();
        upliftBIEPage.setSourceBranch(prev_release);
        upliftBIEPage.setTargetBranch(curr_release);
        TopLevelASBIEPObject BIECAGUplift = testingBIEs.get("BIECAGUplift");
        upliftBIEPage.setPropertyTerm(BIECAGUplift.getPropertyTerm());
        upliftBIEPage.hitSearchButton();
        assertEquals(0, getDriver().findElements(By.xpath("//td//*[contains(text(),\"" + BIECAGUplift.getPropertyTerm() + "\")]//ancestor::tr[1]/td[1]/mat-checkbox/label/span[1]")).size());

        homePage.logout();
        homePage = loginPage().signIn(developer.getLoginId(), developer.getPassword());
        bieMenu = homePage.getBIEMenu();
        upliftBIEPage = bieMenu.openUpliftBIESubMenu();
        upliftBIEPage.setSourceBranch(prev_release);
        upliftBIEPage.setTargetBranch(curr_release);
        upliftBIEPage.setState("QA");
        TopLevelASBIEPObject BIE1QA = testingBIEs.get("BIE1QA");
        upliftBIEPage.setPropertyTerm(BIE1QA.getPropertyTerm());
        upliftBIEPage.hitSearchButton();
        WebElement tr = upliftBIEPage.getTableRecordAtIndex(1);
        WebElement td = upliftBIEPage.getColumnByName(tr, "select");
        click(td);
        click(upliftBIEPage.getNextButton());
        waitFor(Duration.ofSeconds(12000));
        new WebDriverWait(getDriver(), Duration.ofSeconds(10)).until(ExpectedConditions.invisibilityOfElementLocated(By.xpath("//*[contains(@class, 'loading-container')]")));






    }

    @Test
    public void test_TA_29_1_3() {
        usera = getAPIFactory().getAppUserAPI().createRandomEndUserAccount(false);
        thisAccountWillBeDeletedAfterTests(usera);

        BusinessContextObject context = getAPIFactory().getBusinessContextAPI().createRandomBusinessContext(usera);
        NamespaceObject euNamespace = getAPIFactory().getNamespaceAPI().createRandomEndUserNamespace(usera);
        Map<String, TopLevelASBIEPObject> testingBIEs = new HashMap<>();

        HomePage homePage = loginPage().signIn(usera.getLoginId(), usera.getPassword());

        BIEMenu bieMenu = homePage.getBIEMenu();
        ViewEditBIEPage viewEditBIEPage = bieMenu.openViewEditBIESubMenu();
        CreateBIEForSelectBusinessContextsPage createBIEForSelectBusinessContextsPage = viewEditBIEPage.openCreateBIEPage();
        CreateBIEForSelectTopLevelConceptPage createBIEForSelectTopLevelConceptPage = createBIEForSelectBusinessContextsPage.next(Arrays.asList(context));
        EditBIEPage editBIEPage = createBIEForSelectTopLevelConceptPage.createBIE("Enterprise Unit. Enterprise Unit", prev_release);
        EditBIEPage.TopLevelASBIEPPanel topLevelASBIEPPanel = editBIEPage.getTopLevelASBIEPPanel();
        String currentUrl = getDriver().getCurrentUrl();
        BigInteger topLevelAsbiepId = new BigInteger(currentUrl.substring(currentUrl.lastIndexOf("/") + 1));

        TopLevelASBIEPObject topLevelASBIEP = getAPIFactory().getBusinessInformationEntityAPI()
                .getTopLevelASBIEPByID(topLevelAsbiepId);

        topLevelASBIEPPanel.setBusinessTerm("aBusinessTerm");
        topLevelASBIEPPanel.setRemark("aRemark");
        topLevelASBIEPPanel.setStatus("aStatus");
        editBIEPage.hitUpdateButton();

        ACCExtensionViewEditPage accExtensionViewEditPage =
                editBIEPage.extendBIELocallyOnNode("/Enterprise Unit/Extension");
        SelectAssociationDialog selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Product Classification. Classification");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Incorporation Location. Location");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Code List. Code List");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Revised Item Status. Status");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Usage Description. Text");
        selectCCPropertyPage = accExtensionViewEditPage.appendPropertyAtLast("/Enterprise Unit User Extension Group. Details");
        selectCCPropertyPage.selectAssociation("Last Modification Date Time. Date Time");

        accExtensionViewEditPage.setNamespace(euNamespace);
        accExtensionViewEditPage.hitUpdateButton();
        accExtensionViewEditPage.moveToQA();
        accExtensionViewEditPage.moveToProduction();

    }

    @Test
    public void test_TA_29_1_4() {


    }

    @Test
    public void test_TA_29_1_5a() {

    }

    @Test
    public void test_TA_29_1_5b() {

    }

    @Test
    public void test_TA_29_1_5c() {

    }

    @Test
    public void test_TA_29_1_5d() {

    }

    @Test
    public void test_TA_29_1_6a() {

    }

    @Test
    public void test_TA_29_1_6b() {

    }

    @Test
    public void test_TA_29_1_7() {

    }

    @Test
    public void test_TA_29_1_8() {

    }

    @Test
    public void test_TA_29_1_9a() {

    }

    @Test
    public void test_TA_29_1_9b() {

    }

    @Test
    public void test_TA_29_1_9c() {

    }

    @Test
    public void test_TA_29_1_10a() {

    }

    @Test
    public void test_TA_29_1_10b() {

    }

    @Test
    public void test_TA_29_1_11a() {

    }

    @Test
    public void test_TA_29_1_11b() {

    }

    @Test
    public void test_TA_29_1_12() {

    }

    @Test
    public void test_TA_29_1_13() {

    }

}
