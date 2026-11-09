package com.aint.dshremote.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aint.dshremote.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;

/**
 * The collapsible approval + question tray inside the chat. Cards stay alive across screens
 * through {@link PendingStore}: the tray only owns their views and the answer plumbing.
 */
public final class PendingTray {

    private final ScreenHost host;
    private final String sessionId;
    private final Function<String, String> sessionNames;
    private final PendingStore store = PendingStore.get();

    private final View approvalSection;
    private final TextView approvalHeader;
    private final ScrollView approvalScroll;
    private final LinearLayout approvalBox;
    private boolean approvalExpanded = true;
    private final Map<String, View> approvalViews = new HashMap<>();
    private final Map<String, View> questionViews = new HashMap<>();

    public PendingTray(ScreenHost host, View root, String sessionId,
                       Function<String, String> sessionNames) {
        this.host = host;
        this.sessionId = sessionId;
        this.sessionNames = sessionNames;

        approvalSection = root.findViewById(R.id.approval_section);
        approvalHeader = root.findViewById(R.id.approval_header);
        approvalScroll = root.findViewById(R.id.approval_scroll);
        approvalBox = root.findViewById(R.id.approval_box);

        // The tray scrolls instead of growing without bound.
        approvalScroll.getLayoutParams().height = (int) (host.activity().getResources().getDisplayMetrics().heightPixels * 0.34f);
        approvalScroll.setLayoutParams(approvalScroll.getLayoutParams());
        approvalHeader.setOnClickListener(v -> {
            approvalExpanded = !approvalExpanded;
            update();
        });
    }

    /** Rebuilds every still-pending card; called each time the chat is entered. */
    public void showAll() {
        approvalViews.clear();
        questionViews.clear();
        approvalBox.removeAllViews();
        // Approvals and questions from other sessions keep waiting here until answered.
        for (JSONObject pending : new ArrayList<>(store.approvals().values())) {
            addApproval(pending);
        }
        for (JSONObject pending : new ArrayList<>(store.questions().values())) {
            addQuestion(pending);
        }
        update();
    }

    /**
     * Renders one approval. Approvals from other sessions are kept too: they are answered
     * through the same broker, so hiding them only made requests look lost.
     */
    public void addApproval(JSONObject approval) {
        String id = approval.optString("id");
        if (id.isEmpty() || approvalViews.containsKey(id)) {
            return;
        }

        View card = host.activity().getLayoutInflater().inflate(R.layout.item_approval, approvalBox, false);
        TextView title = card.findViewById(R.id.approval_title);
        TextView reasonView = card.findViewById(R.id.approval_reason);
        Button allow = card.findViewById(R.id.approval_allow);
        Button deny = card.findViewById(R.id.approval_deny);

        String tool = approval.optString("toolName", "工具调用");
        String session = approval.optString("sessionId", "");
        String label = sessionId != null && sessionId.equals(session)
                ? "待审批 · " + tool
                : "待审批 · " + tool + "（" + sessionNameOf(session) + "）";
        title.setText(label);

        String reason = approval.optString("reason", "");
        reasonView.setText(reason);
        reasonView.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);

        allow.setOnClickListener(v -> answer(id, "allowed-once", card));
        deny.setOnClickListener(v -> answer(id, "rejected", card));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = host.dp(8);

        approvalViews.put(id, card);
        approvalBox.addView(card, params);
        update();
    }

    /**
     * Renders one parked `user-questions/request`. Each question offers its own options; the
     * card is answered either by tapping a single option (one question, single choice) or by
     * using 提交, which also carries a hand-written answer.
     */
    public void addQuestion(JSONObject question) {
        String id = question.optString("id");
        if (id.isEmpty() || questionViews.containsKey(id)) {
            return;
        }

        JSONArray items = question.optJSONArray("questions");
        if (items == null || items.length() == 0) {
            return;
        }

        View card = host.activity().getLayoutInflater().inflate(R.layout.item_question, approvalBox, false);
        TextView title = card.findViewById(R.id.question_title);
        LinearLayout optionsBox = card.findViewById(R.id.question_options);
        Button submit = card.findViewById(R.id.question_submit);

        String session = question.optString("sessionId", "");
        boolean current = sessionId != null && sessionId.equals(session);
        title.setText(current ? "需要你决定" : "需要你决定（" + sessionNameOf(session) + "）");

        // One map per question keeps the selection state without extra view lookups.
        Map<String, LinkedHashSet<String>> selected = new HashMap<>();
        Map<String, EditText> customFields = new HashMap<>();
        boolean singleQuickAnswer = items.length() == 1;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String qid = item.optString("id", String.valueOf(i));
            selected.put(qid, new LinkedHashSet<>());

            TextView label = new TextView(host.activity());
            label.setText(item.optString("question", "请选择"));
            label.setTextSize(13);
            label.setTextColor(UiKit.color(host.activity(), R.color.text));
            label.setPadding(0, host.dp(4), 0, host.dp(4));
            optionsBox.addView(label);

            String detail = item.optString("detail", "");
            if (!detail.isEmpty()) {
                TextView detailView = new TextView(host.activity());
                detailView.setText(detail);
                detailView.setTextSize(12);
                detailView.setTextColor(UiKit.color(host.activity(), R.color.muted));
                detailView.setPadding(0, 0, 0, host.dp(4));
                optionsBox.addView(detailView);
            }

            JSONArray options = item.optJSONArray("options");
            boolean multi = item.optBoolean("multiSelect");
            int count = options == null ? 0 : options.length();
            for (int j = 0; j < count; j++) {
                JSONObject option = options.optJSONObject(j);
                if (option == null) {
                    continue;
                }
                String optionLabel = option.optString("label", "");
                if (optionLabel.isEmpty()) {
                    continue;
                }

                Button button = new Button(host.activity(), null, 0, R.style.Widget_Btn);
                button.setText(optionLabel);
                button.setAllCaps(false);
                button.setTextSize(13);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42));
                params.bottomMargin = host.dp(6);
                optionsBox.addView(button, params);

                button.setOnClickListener(v -> {
                    LinkedHashSet<String> bucket = selected.get(qid);
                    if (bucket == null) {
                        return;
                    }
                    if (!multi) {
                        bucket.clear();
                    }
                    if (!bucket.remove(optionLabel)) {
                        bucket.add(optionLabel);
                    }
                    button.setSelected(bucket.contains(optionLabel));
                    button.setTextColor(UiKit.color(host.activity(),
                            bucket.contains(optionLabel) ? R.color.accent : R.color.text));

                    // One question with a single choice: the tap itself is the answer.
                    if (singleQuickAnswer && !multi && !bucket.isEmpty()) {
                        submitQuestion(id, selected, customFields, card);
                    }
                });
            }

            EditText custom = new EditText(host.activity());
            custom.setHint("其它答案（可选）");
            custom.setTextSize(13);
            custom.setBackgroundResource(R.drawable.bg_input);
            custom.setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8));
            LinearLayout.LayoutParams customParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            customParams.topMargin = host.dp(4);
            customParams.bottomMargin = host.dp(4);
            optionsBox.addView(custom, customParams);
            customFields.put(qid, custom);
        }

        submit.setOnClickListener(v -> submitQuestion(id, selected, customFields, card));
        // Always available: even a single-choice question may be answered with free text.
        submit.setVisibility(View.VISIBLE);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = host.dp(8);

        questionViews.put(id, card);
        approvalBox.addView(card, params);
        update();
    }

    /** Removes the card of an approval that was resolved elsewhere. */
    public void removeApproval(String id) {
        View view = approvalViews.remove(id);
        if (view != null) {
            approvalBox.removeView(view);
        }
        update();
    }

    /** Removes the card of a question that was resolved elsewhere. */
    public void removeQuestion(String id) {
        View view = questionViews.remove(id);
        if (view != null) {
            approvalBox.removeView(view);
        }
        update();
    }

    /** Collects the current selections into the wire shape and posts them. */
    private void submitQuestion(String id, Map<String, LinkedHashSet<String>> selected,
                                Map<String, EditText> customFields, View card) {
        try {
            JSONArray answers = new JSONArray();
            for (Map.Entry<String, LinkedHashSet<String>> entry : selected.entrySet()) {
                JSONObject answer = new JSONObject();
                answer.put("id", entry.getKey());
                JSONArray picked = new JSONArray();
                for (String label : entry.getValue()) {
                    picked.put(label);
                }
                answer.put("selected", picked);
                EditText custom = customFields.get(entry.getKey());
                String text = custom == null ? "" : custom.getText().toString().trim();
                if (!text.isEmpty()) {
                    answer.put("custom", text);
                }
                if (picked.length() > 0 || !text.isEmpty()) {
                    answers.put(answer);
                }
            }
            if (answers.length() == 0) {
                host.toast("请先选择或填写一个答案");
                return;
            }
            answerQuestion(id, answers, card);
        } catch (JSONException error) {
            host.toast("答案格式有误");
        }
    }

    private void answerQuestion(String id, JSONArray answers, View card) {
        host.io().execute(() -> {
            try {
                host.api().answerQuestion(id, answers);
            } catch (Exception exception) {
                host.main().post(() -> host.toast(host.friendly(exception)));
            }
            host.main().post(() -> {
                store.questions().remove(id);
                questionViews.remove(id);
                approvalBox.removeView(card);
                update();
            });
        });
    }

    /** Short label for a session id, using the cached workspace listing. */
    private String sessionNameOf(String id) {
        String title = sessionNames.apply(id);
        return title == null ? "其它会话" : title;
    }

    /** Header shows how many items are waiting; the list scrolls and can be folded away. */
    private void update() {
        int count = approvalViews.size() + questionViews.size();
        if (count == 0) {
            approvalSection.setVisibility(View.GONE);
            approvalExpanded = true;
            return;
        }

        approvalSection.setVisibility(View.VISIBLE);
        approvalHeader.setText("待处理 " + count + " 项 " + (approvalExpanded ? "▾" : "▸"));
        approvalScroll.setVisibility(approvalExpanded ? View.VISIBLE : View.GONE);

        // Question cards carry options plus a submit button, so they need more room than a
        // two-button approval card.
        int heightPx = (int) (host.activity().getResources().getDisplayMetrics().heightPixels
                * (questionViews.isEmpty() ? 0.34f : 0.55f));
        ViewGroup.LayoutParams params = approvalScroll.getLayoutParams();
        if (params.height != heightPx) {
            params.height = heightPx;
            approvalScroll.setLayoutParams(params);
        }
    }

    private void answer(String id, String outcome, View card) {
        host.io().execute(() -> {
            try {
                host.api().answer(id, outcome);
            } catch (Exception exception) {
                host.main().post(() -> host.toast(host.friendly(exception)));
            }
            host.main().post(() -> {
                store.approvals().remove(id);
                update();
                approvalViews.remove(id);
                approvalBox.removeView(card);
                update();
            });
        });
    }
}
