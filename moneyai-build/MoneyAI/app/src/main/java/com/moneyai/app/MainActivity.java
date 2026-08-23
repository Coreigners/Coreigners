package com.moneyai.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String[] CATEGORIES = {
            "식비", "카페", "편의점", "쇼핑/의류", "교통", "주유/차량", "구독", "의료",
            "취미/문화", "여행", "통신", "공과금", "보험", "교육/도서", "생활", "기타"
    };

    private LinearLayout content;
    private TextView syncStatus;
    private Button syncButton;
    private int currentTab = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        showHome();
        refreshSyncStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        renderCurrentTab();
        refreshSyncStatus();
    }

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(36));
        scroll.addView(root);

        root.addView(text("Money AI", 30, true));
        TextView subtitle = text("KB국민 체크 · 네이버 현대카드 · KB국민은행", 14, false);
        subtitle.setPadding(0, dp(4), 0, dp(6));
        root.addView(subtitle);

        TextView mode = text("알림 수집을 중단하고 실제 금융 거래내역 기준으로 계산합니다.", 13, false);
        mode.setPadding(0, 0, 0, dp(12));
        root.addView(mode);

        syncStatus = text("데이터 연결 확인 중…", 13, true);
        root.addView(syncStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(6));
        syncButton = new Button(this);
        syncButton.setText("지금 동기화");
        syncButton.setOnClickListener(v -> syncNow());
        actions.addView(syncButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button config = new Button(this);
        config.setText("API 연결 설정");
        config.setOnClickListener(v -> showConfigDialog());
        actions.addView(config, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions);

        LinearLayout demoActions = new LinearLayout(this);
        demoActions.setOrientation(LinearLayout.HORIZONTAL);
        Button demo = new Button(this);
        demo.setText("화면 테스트 데이터");
        demo.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("테스트 데이터 넣기")
                .setMessage("실제 동기화 전 화면 확인용 4개월 데이터를 넣을까요? 현재 거래 기록은 교체됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("넣기", (d, w) -> {
                    MoneyStore.seedDemo(this);
                    renderCurrentTab();
                    refreshSyncStatus();
                }).show());
        demoActions.addView(demo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button reset = new Button(this);
        reset.setText("데이터 초기화");
        reset.setOnClickListener(v -> confirmReset());
        demoActions.addView(reset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(demoActions);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(12), 0, dp(8));
        tabs.addView(tabButton("홈", 0), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabButton("월별", 1), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabButton("거래", 2), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);
        return scroll;
    }

    private Button tabButton(String label, int tab) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> {
            currentTab = tab;
            renderCurrentTab();
        });
        return b;
    }

    private void renderCurrentTab() {
        if (content == null) return;
        if (currentTab == 1) showMonths();
        else if (currentTab == 2) showTransactions();
        else showHome();
    }

    private void showHome() {
        currentTab = 0;
        content.removeAllViews();

        MoneyStore.Balance balance = MoneyStore.balance(this);
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        MoneyStore.MonthSummary current = MoneyStore.monthSummary(this, year, month);

        Calendar prevCal = (Calendar) now.clone();
        prevCal.add(Calendar.MONTH, -1);
        MoneyStore.MonthSummary previous = MoneyStore.monthSummary(this,
                prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH) + 1);

        content.addView(section("지금 내 돈"));
        if (balance.updatedAt == 0L) {
            TextView noBalance = text("—", 34, true);
            noBalance.setPadding(0, 0, 0, dp(4));
            content.addView(noBalance);
            content.addView(text("아직 실제 잔액을 동기화하지 않았습니다.", 14, false));
        } else {
            TextView available = text(won(balance.availableMoney()), 34, true);
            available.setPadding(0, 0, 0, dp(3));
            content.addView(available);
            content.addView(text("실질 가용자금", 14, true));
            content.addView(text("KB국민은행 잔액  " + won(balance.kbBalance), 15, false));
            String pendingLabel = balance.pendingEstimated ? "현대카드 미정산 추정  −" : "현대카드 결제예정  −";
            content.addView(text(pendingLabel + won(balance.hyundaiPending), 15, false));
            TextView note = text(balance.pendingEstimated
                    ? "※ 현대카드 미정산액은 마지막 카드대금 출금 이후 승인내역으로 추정합니다. 잔액 자체는 KB 계좌 조회값입니다."
                    : "현대카드 결제예정액을 반영한 금액입니다.", 12, false);
            note.setPadding(0, dp(5), 0, dp(8));
            content.addView(note);
        }

        content.addView(section(month + "월 돈의 흐름"));
        content.addView(metricRow("수입", current.income));
        content.addView(metricRow("지출", -current.expense));
        content.addView(metricRow("이번 달 남긴 돈", current.saved));

        long expenseDelta = current.expense - previous.expense;
        TextView compare = text("지난달 대비 지출  " + signedWon(expenseDelta), 15, true);
        compare.setPadding(0, dp(8), 0, dp(3));
        content.addView(compare);
        if (previous.expense > 0L) {
            long pct = Math.round(expenseDelta * 100.0 / previous.expense);
            content.addView(text("지난달 " + won(previous.expense) + " → 이번 달 " + won(current.expense)
                    + "  (" + (pct >= 0 ? "+" : "") + pct + "%)", 13, false));
        }

        content.addView(section("어디에 더 쓰고 덜 썼나"));
        Map<String, Long> currentCats = MoneyStore.categoryTotals(this, year, month);
        Map<String, Long> prevCats = MoneyStore.categoryTotals(this,
                prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH) + 1);
        renderCategoryComparison(currentCats, prevCats);

        TextView principle = text("카드대금 출금과 내 계좌끼리의 자금이동은 지출에서 제외합니다. 체크카드는 카드 승인내역을 지출로 잡고 동일한 은행 출금은 중복 계산하지 않습니다.", 12, false);
        principle.setPadding(0, dp(16), 0, dp(10));
        content.addView(principle);
    }

    private void showMonths() {
        currentTab = 1;
        content.removeAllViews();
        content.addView(section("월별 정리"));
        TextView guide = text("매달 수입 · 실제 소비지출 · 남긴 돈을 같은 기준으로 비교합니다.", 13, false);
        guide.setPadding(0, 0, 0, dp(8));
        content.addView(guide);

        List<MoneyStore.MonthSummary> months = MoneyStore.recentMonths(this, 12);
        for (MoneyStore.MonthSummary m : months) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(12), 0, dp(12));
            box.addView(text(m.year + "년 " + m.month + "월", 18, true));
            box.addView(text("수입  " + won(m.income) + "   ·   지출  " + won(m.expense), 14, false));
            box.addView(text("남긴 돈  " + signedWon(m.saved), 15, true));
            final int y = m.year;
            final int mon = m.month;
            box.setOnClickListener(v -> showMonthDetail(y, mon));
            content.addView(box);
        }
    }

    private void showMonthDetail(int year, int month) {
        MoneyStore.MonthSummary summary = MoneyStore.monthSummary(this, year, month);
        Map<String, Long> cats = MoneyStore.categoryTotals(this, year, month);
        StringBuilder body = new StringBuilder();
        body.append("수입  ").append(won(summary.income)).append("\n")
                .append("지출  ").append(won(summary.expense)).append("\n")
                .append("남긴 돈  ").append(signedWon(summary.saved)).append("\n\n")
                .append("카테고리별 지출\n");
        if (cats.isEmpty()) body.append("기록 없음");
        else for (Map.Entry<String, Long> e : cats.entrySet()) {
            body.append(e.getKey()).append("  ").append(won(e.getValue())).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(year + "년 " + month + "월")
                .setMessage(body.toString().trim())
                .setPositiveButton("확인", null)
                .show();
    }

    private void showTransactions() {
        currentTab = 2;
        content.removeAllViews();
        content.addView(section("거래 내역"));
        TextView guide = text("가맹점 분류가 틀리면 소비 거래를 눌러 수정하세요. 같은 가맹점은 다음 동기화부터 수정한 분류를 기억합니다.", 13, false);
        guide.setPadding(0, 0, 0, dp(8));
        content.addView(guide);

        List<MoneyStore.Tx> list = MoneyStore.all(this);
        if (list.isEmpty()) {
            content.addView(text("아직 거래가 없습니다. CODEF 연결 후 동기화하거나 화면 테스트 데이터를 넣어 확인할 수 있습니다.", 14, false));
            return;
        }

        SimpleDateFormat f = new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA);
        int count = 0;
        for (MoneyStore.Tx tx : list) {
            if (count++ >= 150) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));
            String amountText;
            if (MoneyStore.INCOME.equals(tx.direction) || MoneyStore.REFUND.equals(tx.direction)) {
                amountText = "+" + won(tx.amount);
            } else if (MoneyStore.TRANSFER.equals(tx.direction) || MoneyStore.CARD_PAYMENT.equals(tx.direction)) {
                amountText = won(tx.amount) + " (합계 제외)";
            } else {
                amountText = "−" + won(tx.amount);
            }
            row.addView(text(tx.merchant + "   " + amountText, 16, true));
            row.addView(text(tx.category + " · " + tx.paymentMethod + " · " + f.format(new Date(tx.timestamp)), 12, false));
            if (MoneyStore.EXPENSE.equals(tx.direction) || MoneyStore.REFUND.equals(tx.direction)) {
                row.setOnClickListener(v -> chooseCategory(tx));
            }
            content.addView(row);
        }
    }

    private void renderCategoryComparison(Map<String, Long> current, Map<String, Long> previous) {
        LinkedHashMap<String, Long> all = new LinkedHashMap<>();
        for (String k : current.keySet()) all.put(k, current.get(k));
        for (String k : previous.keySet()) if (!all.containsKey(k)) all.put(k, 0L);

        ArrayList<String> keys = new ArrayList<>(all.keySet());
        keys.sort((a, b) -> Long.compare(current.getOrDefault(b, 0L), current.getOrDefault(a, 0L)));
        if (keys.isEmpty()) {
            content.addView(text("이번 달 지출 기록이 없습니다.", 14, false));
            return;
        }
        int shown = 0;
        for (String k : keys) {
            if (shown++ >= 8) break;
            long now = current.getOrDefault(k, 0L);
            long prev = previous.getOrDefault(k, 0L);
            long delta = now - prev;
            content.addView(text(k + "  " + won(now) + "   " + signedWon(delta), 14, false));
        }
    }

    private View metricRow(String label, long value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 16, false);
        TextView amount = text(signedWon(value), 18, true);
        amount.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(amount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private void chooseCategory(MoneyStore.Tx tx) {
        new AlertDialog.Builder(this)
                .setTitle(tx.merchant + " 분류")
                .setItems(CATEGORIES, (d, which) -> {
                    MoneyStore.reclassify(this, tx, CATEGORIES[which]);
                    renderCurrentTab();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void syncNow() {
        CodefClient.Config config = CodefClient.loadConfig(this);
        if (!config.complete()) {
            showConfigDialog();
            return;
        }
        syncButton.setEnabled(false);
        syncStatus.setText("⏳ KB/현대카드 거래와 잔액 동기화 중…");
        new Thread(() -> {
            CodefClient.SyncResult result = CodefClient.sync(this, config);
            runOnUiThread(() -> {
                syncButton.setEnabled(true);
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                refreshSyncStatus();
                renderCurrentTab();
            });
        }).start();
    }

    private void showConfigDialog() {
        CodefClient.Config saved = CodefClient.loadConfig(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(4), pad, 0);

        TextView info = text("CODEF 개발자 정보만 입력합니다. KB/현대카드 로그인 비밀번호는 이 앱이나 채팅에 입력하지 않습니다. Connected ID는 CODEF에서 금융기관 연결 후 발급받은 값을 사용합니다.", 12, false);
        info.setPadding(0, 0, 0, dp(8));
        box.addView(info);

        EditText clientId = input("CODEF Client ID", saved.clientId, false);
        EditText secret = input("CODEF Client Secret", saved.clientSecret, true);
        EditText connected = input("Connected ID", saved.connectedId, false);
        EditText birth = input("생년월일 YYYYMMDD (카드사 요구 시)", saved.birthDate, false);
        birth.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox production = new CheckBox(this);
        production.setText("정식 API 사용 (체크 안 하면 Demo/Development)");
        production.setChecked(saved.production);

        box.addView(clientId);
        box.addView(secret);
        box.addView(connected);
        box.addView(birth);
        box.addView(production);

        new AlertDialog.Builder(this)
                .setTitle("CODEF 연결 설정")
                .setView(box)
                .setNegativeButton("취소", null)
                .setNeutralButton("설정 삭제", (d, w) -> {
                    CodefClient.clearConfig(this);
                    refreshSyncStatus();
                })
                .setPositiveButton("저장", (d, w) -> {
                    CodefClient.Config c = new CodefClient.Config(
                            clientId.getText().toString(),
                            secret.getText().toString(),
                            connected.getText().toString(),
                            birth.getText().toString(),
                            production.isChecked());
                    CodefClient.saveConfig(this, c);
                    refreshSyncStatus();
                    if (c.complete()) Toast.makeText(this, "저장됨. '지금 동기화'를 누르세요.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private EditText input(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setText(value);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void refreshSyncStatus() {
        if (syncStatus == null) return;
        long last = MoneyStore.lastSync(this);
        CodefClient.Config c = CodefClient.loadConfig(this);
        if (!c.complete()) {
            syncStatus.setText("○ 실제 데이터 연결 전 · CODEF 연결 설정이 필요함");
        } else if (last == 0L) {
            syncStatus.setText("○ CODEF 설정 저장됨 · 첫 동기화 필요");
        } else {
            syncStatus.setText("✓ 마지막 동기화  " + new SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(new Date(last)));
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Money AI 데이터 초기화")
                .setMessage("거래, 잔액, 분류 학습값을 모두 지울까요? CODEF 연결 설정은 유지합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("초기화", (d, w) -> {
                    MoneyStore.clear(this);
                    renderCurrentTab();
                    refreshSyncStatus();
                })
                .show();
    }

    private TextView section(String s) {
        TextView v = text(s, 16, true);
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
        return NumberFormat.getNumberInstance(Locale.KOREA).format(Math.abs(n)) + "원";
    }

    private String signedWon(long n) {
        if (n > 0) return "+" + won(n);
        if (n < 0) return "−" + won(n);
        return "0원";
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
