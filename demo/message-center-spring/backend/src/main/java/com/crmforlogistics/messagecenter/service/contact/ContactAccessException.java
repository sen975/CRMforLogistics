package com.crmforlogistics.messagecenter.service.contact;

/** 联系人不存在或当前用户不能访问；内部保留类型，外部不区分原因。 */
public class ContactAccessException extends IllegalArgumentException {

    public ContactAccessException() {
        super("Contact not found");
    }
}
