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
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private TextView accessStatus;
    private TextView todayExpenseView;
    private TextView monthExpenseView;
    private TextView monthIncomeView;
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
        TextView subtitle = text("토스 거래 알림을 폰 안에서 자동 기록", 15, false);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        accessStatus = text("알림 접근 확인 중…", 15, true);
        root.addView(accessStatus);

        Button access = new Button(this);
        access.setText("알림 접근 권한 열기");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access);

        TextView privacy = text("현재 버전은 토스 알림만 읽고 거래 데이터는 이 기기에만 저장합니다.", 13, false);
        privacy.setPadding(0, dp(8), 0, dp(20));
        root.addView(privacy);

        root.addView(section("오늘 지출"));
        todayExpenseView = text("0원", 28, true);
        root.addView(todayExpenseView);

        root.addView(section("이번 달"));
        monthExpenseView = text("지출 0원", 20, true);
        root.addView(monthExpenseView);
        monthIncomeView = text("수입 0원", 20, false);
        root.addView(monthIncomeView);

        Button test = new Button(this);
        test.setText("테스트 거래 5,800원 추가");
        test.setOnClickListener(v -> {
            MoneyStore.add(this, new MoneyStore.Tx(5800, "EXPENSE", "스타벅스", "카페", System.currentTimeMillis()));
            refresh();
        });
        root.addView(test);

        Button clear = new Button(this);
        clear.setText("모든 기록 삭제");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("모든 거래 기록 삭제")
                .setMessage("이 기기에 저장된 Money AI 거래 기록을 전부 지울까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> { MoneyStore.clear(this); refresh(); })
                .show());
        root.addView(clear);

        TextView rt = section("최근 거래");
        rt.setPadding(0, dp(24), 0, dp(10));
        root.addView(rt);
        transactionList = new LinearLayout(this);
        transactionList.setOrientation(LinearLayout.VERTICAL);
        root.addView(transactionList);
        return scroll;
    }

    private void refresh() {
        accessStatus.setText(isNotificationAccessGranted() ? "✓ 알림 접근 허용됨" : "⚠ 알림 접근 권한이 필요함");
        todayExpenseView.setText(won(MoneyStore.sum(this, "EXPENSE", true)));
        monthExpenseView.setText("지출 " + won(MoneyStore.sum(this, "EXPENSE", false)));
        monthIncomeView.setText("수입 " + won(MoneyStore.sum(this, "INCOME", false)));
        renderTransactions(MoneyStore.all(this));
    }

    private void renderTransactions(List<MoneyStore.Tx> list) {
        transactionList.removeAllViews();
        if (list.isEmpty()) {
            transactionList.addView(text("아직 기록된 거래가 없습니다. 권한을 허용한 뒤 토스 거래 알림이 오면 자동으로 쌓입니다.", 14, false));
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("MM.dd HH:mm", Locale.KOREA);
        int count = 0;
        for (MoneyStore.Tx tx : list) {
            if (count++ >= 50) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            String sign = "INCOME".equals(tx.direction) ? "+" : "−";
            row.addView(text(tx.merchant + "   " + sign + won(tx.amount), 17, true));
            row.addView(text(tx.category + " · " + f.format(new Date(tx.timestamp)), 13, false));
            transactionList.addView(row);
        }
    }

    private boolean isNotificationAccessGranted() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(this, MoneyNotificationListener.class);
        return enabled.contains(me.flattenToString()) || enabled.contains(getPackageName());
    }

    private TextView section(String s) { TextView v = text(s, 15, true); v.setPadding(0, dp(18), 0, dp(8)); return v; }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private String won(long n) { return NumberFormat.getNumberInstance(Locale.KOREA).format(n) + "원"; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
