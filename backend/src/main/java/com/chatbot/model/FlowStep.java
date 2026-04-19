package com.chatbot.model;

public enum FlowStep {
    // Payment flow
    ASK_SUBSCRIBER_NO,
    FETCH_BILL,
    SHOW_AMOUNT,
    CONFIRM_PAYMENT,
    PROCESS_PAYMENT,
    DONE,

    // İBB complaint flow
    ASK_COMPLAINT_DESCRIPTION,
    ASK_COMPLAINT_PERSONAL,
    ASK_COMPLAINT_PHOTO,
    CONFIRM_COMPLAINT
}
