package com.aint.dshremote.ui;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Approvals and questions that are still waiting, from any session. They outlive one chat
 * screen so that leaving a conversation and coming back re-renders every pending card; the
 * router writes frames in here and the chat tray removes entries once they are answered.
 */
public final class PendingStore {

    private static final PendingStore INSTANCE = new PendingStore();

    public static PendingStore get() {
        return INSTANCE;
    }

    /** Every approval still waiting, from any session, so none is dropped while off-screen. */
    private final Map<String, JSONObject> approvals = new LinkedHashMap<>();
    /** Questions the agent is waiting on; the same tray renders them next to approvals. */
    private final Map<String, JSONObject> questions = new LinkedHashMap<>();

    private PendingStore() {
    }

    public Map<String, JSONObject> approvals() {
        return approvals;
    }

    public Map<String, JSONObject> questions() {
        return questions;
    }
}
