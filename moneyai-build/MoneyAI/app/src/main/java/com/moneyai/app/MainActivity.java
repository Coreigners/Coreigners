package com.moneyai.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String KB_PAY = "com.kbcard.cxh.appcard";
    private static final String HYUNDAI_CARD = "com.hyundaicard.appcard";
    private static final String KB_BANK = "com.kbstar.kbbank";

    private static final String[] CATEGORIES = {
            "식비", "카페", "편의점", "쇼핑", "교통", "주유/차량", "구독", "의료",
            "문화/여가", "여행", "통신", "생활/공과금", "보험", "교육", "세금",
            "이체/현금", "급여", "입금", "기타수입", "환불/취소", "기타"
    };

    private TextView accessStatus;
    private TextView todayExpenseView;
    private TextView monthExpenseView;
    private TextView monthIncomeView;
    private TextView paymentSummaryView;
    private TextView categorySummaryView;
    private LinearLayout transactionList;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("Money AI", 30, true));
        TextView subtitle = text("KB국민 체크 · 네이버 현대카드 · 국민은행 계좌", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        accessStatus = text("알림 접근 확인 중…", 15, true);
        root.addView(accessStatus);

        Button access = new Button(this);
        access.setText("Money AI 알림 접근 권한 열기");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access);

        TextView guide = text(
                "딱 3개 앱의 거래 알림만 사용합니다. 토스·주식·증권·다른 앱 알림은 무시합니다.\n" +
                "알림은 소리/진동 없이 '조용히' 켜도 됩니다. 카드 승인/취소와 은행 입출금 알림만 켜세요.",
                13, false);
        guide.setPadding(0, dp(8), 0, dp(8));
        root.addView(guide);

        Button kbPaySettings = new Button(this);
        kbPaySettings.setText("KB Pay 거래 알림 설정");
        kbPaySettings.setOnClickListener(v -> openNotificationSettings(KB_PAY));
        root.addView(kbPaySettings);

        Button hyundaiSettings = new Button(this);
        hyundaiSettings.setText("현대카드 거래 알림 설정");
        hyundaiSettings.setOnClickListener(v -> openNotificationSettings(HYUNDAI_CARD));
        root.addView(hyundaiSettings);

        Button kbBankSettings = new Button(this);
        kbBankSettings.setText("KB스타뱅킹 입출금 알림 설정");
        kbBankSettings.setOnClickListener(v -> openNotificationSettings(KB_BANK));
        root.addView(kbBankSettings);

        TextView privacy = text("거래 기록과 분류 학습값은 이 폰 내부에만 저장합니다.", 13, false);
        privacy.setPadding(0, dp(8), 0, dp(14));
        root.addView(privacy);

        root.addView(section("오늘 지출"));
        todayExpenseView = text("0원", 28, true);
        root.addView(todayExpenseView);

        root.addView(section("이번 달"));
        monthExpenseView = text("지출 0원", 20, true);
        root.addView(monthExpenseView);
        monthIncomeView = text("수입 0원", 20, false);
        root.addView(monthIncomeView);

        root.addView(section("결제수단별"));
        paymentSummaryView = text("기록 없음", 15, false);
        root.addView(paymentSummaryView);

        root.addView(section("카테고리별"));
        categorySummaryView = text("기록 없음", 15, false);
        root.addView(categorySummaryView);

        TextView learning = text("분류가 틀리면 최근 거래를 눌러 카테고리를 수정하세요. 같은 가맹점은 다음 거래부터 그 분류를 기억합니다.", 13, false);
        learning.setPadding(0, dp(12), 0, dp(12));
        root.addView(learning);

        Button test = new Button(this);
        test.setText("내 카드 조합 테스트 3건 추가");
        test.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            MoneyStore.add(this, new MoneyStore.Tx(5800, "EXPENSE", "스타벅스", "카페", "KB국민 체크카드", "KB Pay", now));
            MoneyStore.add(this, new MoneyStore.Tx(62900, "EXPENSE", "무신사", "쇼핑", "네이버 현대카드", "현대카드", now + 1000));
            MoneyStore.add(this, new MoneyStore.Tx(3000000, "INCOME", "급여", "급여", "KB국민은행 계좌", "KB스타뱅킹", now + 2000));
            refresh();
        });
        root.addView(test);

        Button clear = new Button(this);
        clear.setText("모든 기록/분류학습 삭제");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Money AI 기록 삭제")
                .setMessage("이 기기에 저장된 거래 기록과 가맹점 분류 학습값을 전부 지울까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> { MoneyStore.clear(this); refresh(); })
                .show());
        root.addView(clear);

        TextView rt = section("최근 거래 · 눌러서 분류 수정");
        rt.setPadding(0, dp(24), 0, dp(10));
        root.addView(rt);
        transactionList = new LinearLayout(this);
        transactionList.setOrientation(LinearLayout.VERTICAL);
        root.addView(transactionList);
        return scroll;
    }

    private void refresh() {
        accessStatus.setText(isNotificationAccessGranted() ? "✓ Money AI 알림 접근 허용됨" : "⚠ Money AI 알림 접근 권한이 필요함");
        todayExpenseView.setText(won(MoneyStore.sum(this, "EXPENSE", true)));
        monthExpenseView.setText("지출 " + won(MoneyStore.sum(this, "EXPENSE", false)));
        monthIncomeView.setText("수입 " + won(MoneyStore.sum(this, "INCOME", false)));

        List<MoneyStore.Tx> all = MoneyStore.all(this);
        renderSummaries(all);
        renderTransactions(all);
    }

    private void renderSummaries(List<MoneyStore.Tx> list) {
        long start = MoneyStore.monthStart();
        Map<String, Long> payments = new HashMap<>();
        Map<String, Long> categories = new HashMap<>();

        for (MoneyStore.Tx tx : list) {
            if (tx.timestamp < start) continue;
            long delta = 0L;
            if ("EXPENSE".equals(tx.direction)) delta = tx.amount;
            else if ("REFUND".equals(tx.direction)) delta = -tx.amount;

            if (delta != 0L) {
                payments.put(tx.paymentMethod, payments.getOrDefault(tx.paymentMethod, 0L) + delta);
            }
            if ("EXPENSE".equals(tx.direction)) {
                categories.put(tx.category, categories.getOrDefault(tx.category, 0L) + tx.amount);
            }
        }

        paymentSummaryView.setText(summary(payments, 6));
        categorySummaryView.setText(summary(categories, 8));
    }

    private String summary(Map<String, Long> map, int maxRows) {
        if (map.isEmpty()) return "기록 없음";
        ArrayList<Map.Entry<String, Long>> rows = new ArrayList<>(map.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Long> e : rows) {
            if (count++ >= maxRows) break;
            if (out.length() > 0) out.append("\n");
            out.append(e.getKey()).append("  ").append(won(e.getValue()));
        }
        return out.toString();
    }

    private void renderTransactions(List<MoneyStore.Tx> list) {
        transactionList.removeAllViews();
        if (list.isEmpty()) {
            transactionList.addView(text("아직 기록된 거래가 없습니다. 위의 거래 알림 3개만 켜두면 새 거래부터 자동으로 쌓입니다.", 14, false));
            return;
        }

        SimpleDateFormat f = new SimpleDateFormat("MM.dd HH:mm", Locale.KOREA);
        int count = 0;
        for (MoneyStore.Tx tx : list) {
            if (count++ >= 70) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(11), 0, dp(11));

            String sign;
            if ("INCOME".equals(tx.direction)) sign = "+";
            else if ("REFUND".equals(tx.direction)) sign = "↩ +";
            else sign = "−";

            row.addView(text(tx.merchant + "   " + sign + won(tx.amount), 17, true));
            row.addView(text(tx.category + " · " + tx.paymentMethod + " · " + f.format(new Date(tx.timestamp)), 13, false));
            row.setOnClickListener(v -> chooseCategory(tx));
            transactionList.addView(row);
        }
    }

    private void chooseCategory(MoneyStore.Tx tx) {
        new AlertDialog.Builder(this)
                .setTitle(tx.merchant + " 분류")
                .setItems(CATEGORIES, (d, which) -> {
                    MoneyStore.reclassify(this, tx, CATEGORIES[which]);
                    refresh();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void openNotificationSettings(String packageName) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
            startActivity(i);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isNotificationAccessGranted() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(this, MoneyNotificationListener.class);
        return enabled.contains(me.flattenToString()) || enabled.contains(getPackageName());
    }

    private TextView section(String s) {
        TextView v = text(s, 15, true);
        v.setPadding(0, dp(18), 0, dp(8));
        return v;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private String won(long n) {
        String prefix = n < 0 ? "−" : "";
        return prefix + NumberFormat.getNumberInstance(Locale.KOREA).format(Math.abs(n)) + "원";
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
