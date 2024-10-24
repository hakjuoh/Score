package org.oagi.score.e2e.impl.page.bie;

import org.oagi.score.e2e.impl.page.BasePageImpl;
import org.oagi.score.e2e.obj.BusinessContextObject;
import org.oagi.score.e2e.obj.TopLevelASBIEPObject;
import org.oagi.score.e2e.page.bie.IncludeMetaHeaderProfileBIEDialog;
import org.openqa.selenium.*;

import static org.oagi.score.e2e.impl.PageHelper.*;

public class IncludeMetaHeaderProfileBIEDialogImpl implements IncludeMetaHeaderProfileBIEDialog {

    private static final By STATE_SELECT_FIELD_LOCATOR =
            By.xpath("//*[contains(text(), \"State\")]//ancestor::mat-form-field[1]//mat-select//div[contains(@class, \"mat-select-arrow-wrapper\")]");

    private static final By OWNER_SELECT_FIELD_LOCATOR =
            By.xpath("//mat-label[contains(text(), \"Owner\")]//ancestor::div[1]/mat-select[1]");

    private static final By UPDATER_SELECT_FIELD_LOCATOR =
            By.xpath("//*[contains(text(), \"Updater\")]//ancestor::div[1]/mat-select[1]");

    private static final By BUSINESS_CONTEXT_FIELD_LOCATOR =
            By.xpath("//span[contains(text(), \"Business Context\")]//ancestor::mat-form-field//input");

    private static final By UPDATED_START_DATE_FIELD_LOCATOR =
            By.xpath("//input[contains(@data-placeholder, \"Updated start date\")]");

    private static final By UPDATED_END_DATE_FIELD_LOCATOR =
            By.xpath("//input[contains(@data-placeholder, \"Updated end date\")]");
    private static final By SEARCH_BUTTON_LOCATOR =
            By.xpath("//span[contains(text(), \"Search\")]//ancestor::button[1]");
    private static final By SELECT_BUTTON_LOCATOR =
            By.xpath("//span[contains(text(), \"Select\")]//ancestor::button[1]");
    private final BasePageImpl parent;

    public IncludeMetaHeaderProfileBIEDialogImpl(BasePageImpl parent) {
        this.parent = parent;
    }

    private WebDriver getDriver() {
        return this.parent.getDriver();
    }

    @Override
    public boolean isOpened() {
        try {
            getTitle();
        } catch (TimeoutException e) {
            return false;
        }
        return true;
    }

    @Override
    public WebElement getTitle() {
        return visibilityOfElementLocated(getDriver(), By.xpath("//mat-dialog-container//mat-card-title"));
    }

    @Override
    public WebElement getBusinessContextField() {
        return visibilityOfElementLocated(getDriver(), BUSINESS_CONTEXT_FIELD_LOCATOR);
    }

    @Override
    public void setBusinessContext(String den) {
        sendKeys(getBusinessContextField(), den);
    }

    @Override
    public WebElement getSearchButton() {
        return elementToBeClickable(getDriver(), SEARCH_BUTTON_LOCATOR);
    }

    @Override
    public void hitSearchButton() {
        click(getSearchButton());
        invisibilityOfLoadingContainerElement(getDriver());
    }

    @Override
    public WebElement getTableRecordByValue(String value) {
        return visibilityOfElementLocated(getDriver(), By.xpath("//td//span[contains(text(), \"" + value + "\")]/ancestor::tr"));
    }

    @Override
    public WebElement getColumnByName(WebElement tableRecord, String columnName) {
        return tableRecord.findElement(By.className("mat-column-" + columnName));
    }

    @Override
    public void selectMetaHeaderProfile(TopLevelASBIEPObject metaHeaderASBIEP, BusinessContextObject context) {
        retry(() -> {
            WebElement tr = getTableRecordByValue(metaHeaderASBIEP.getDen());
            WebElement td = getColumnByName(tr, "select");
            WebElement ele = td.findElement(By.xpath("mat-checkbox/label/span[1]"));
            click(getDriver(), ele);
        });
        click(getSelectButton());
        invisibilityOfLoadingContainerElement(getDriver());
    }

    @Override
    public WebElement getSelectButton() {
        return elementToBeClickable(getDriver(), SELECT_BUTTON_LOCATOR);
    }
}
