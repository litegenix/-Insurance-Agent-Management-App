package com.insuranceagent.activities;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.insuranceagent.api.ApiClient;
import com.insuranceagent.api.ApiService;
import com.insuranceagent.databinding.ActivityDashboardBinding;
import com.insuranceagent.utils.CurrencyUtils;
import com.insuranceagent.utils.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {

    private ActivityDashboardBinding binding;
    private ApiService               apiService;
    private SessionManager           sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding        = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        apiService     = ApiClient.getService(this);
        sessionManager = new SessionManager(this);

        binding.tvAgentName.setText("Welcome, " + sessionManager.getAgentName());

        setupCharts();
        loadDashboardData();

        binding.swipeRefresh.setOnRefreshListener(this::loadDashboardData);
    }

    // ─── Load Data from API ───────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void loadDashboardData() {
        binding.progressBar.setVisibility(View.VISIBLE);

        apiService.getDashboardData().enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call,
                                   Response<Map<String, Object>> response) {
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> data = response.body();
                    updateSummaryCards(data);
                    updateCollectionChart(data);
                    updatePaymentStatusPie(data);
                    updateTargetProgress(data);
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
            }
        });
    }

    // ─── Summary Cards ────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void updateSummaryCards(Map<String, Object> data) {
        // Total Clients
        Object totalClients = data.get("total_clients");
        binding.tvTotalClients.setText(totalClients != null ? totalClients.toString() : "0");

        // Total Policies
        Object totalPolicies = data.get("total_policies");
        binding.tvTotalPolicies.setText(totalPolicies != null ? totalPolicies.toString() : "0");

        // Monthly Collection
        Object monthlyCollection = data.get("monthly_collection");
        double collected = monthlyCollection instanceof Double ? (Double) monthlyCollection : 0.0;
        binding.tvMonthlyCollection.setText(CurrencyUtils.format(collected));

        // Overdue Payments
        Object overdue = data.get("overdue_count");
        binding.tvOverdueCount.setText(overdue != null ? overdue.toString() : "0");
        if (overdue instanceof Double && (Double) overdue > 0) {
            binding.cardOverdue.setCardBackgroundColor(Color.parseColor("#FFEBEE"));
        }

        // Pending Tasks
        Object pendingTasks = data.get("pending_tasks");
        binding.tvPendingTasks.setText(pendingTasks != null ? pendingTasks.toString() : "0");

        // Commission This Month
        Object commission = data.get("commission_earned");
        double comm = commission instanceof Double ? (Double) commission : 0.0;
        binding.tvCommission.setText(CurrencyUtils.format(comm));
    }

    // ─── Bar Chart: Monthly Collections (last 6 months) ──────────────────────
    @SuppressWarnings("unchecked")
    private void updateCollectionChart(Map<String, Object> data) {
        List<Double> monthlyData = (List<Double>) data.get("monthly_trend");
        if (monthlyData == null) return;

        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < monthlyData.size(); i++) {
            entries.add(new BarEntry(i, monthlyData.get(i).floatValue()));
        }

        BarDataSet dataSet = new BarDataSet(entries, "Monthly Collections (PKR)");
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
        dataSet.setValueTextSize(10f);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return CurrencyUtils.formatShort((double) value);
            }
        });

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.7f);

        BarChart chart = binding.barChartCollections;
        chart.setData(barData);
        chart.getDescription().setEnabled(false);
        chart.setFitBars(true);
        chart.animateY(800);
        chart.invalidate();
    }

    // ─── Pie Chart: Payment Status Breakdown ──────────────────────────────────
    @SuppressWarnings("unchecked")
    private void updatePaymentStatusPie(Map<String, Object> data) {
        Map<String, Double> statusMap = (Map<String, Double>) data.get("payment_status_breakdown");
        if (statusMap == null) return;

        List<PieEntry> entries = new ArrayList<>();
        if (statusMap.containsKey("Collected")) entries.add(new PieEntry(statusMap.get("Collected").floatValue(), "Collected"));
        if (statusMap.containsKey("Deposited")) entries.add(new PieEntry(statusMap.get("Deposited").floatValue(), "Deposited"));
        if (statusMap.containsKey("Pending"))   entries.add(new PieEntry(statusMap.get("Pending").floatValue(),   "Pending"));
        if (statusMap.containsKey("Overdue"))   entries.add(new PieEntry(statusMap.get("Overdue").floatValue(),   "Overdue"));

        PieDataSet dataSet = new PieDataSet(entries, "Payment Status");
        dataSet.setColors(
                Color.parseColor("#4CAF50"),  // Collected - green
                Color.parseColor("#2196F3"),  // Deposited - blue
                Color.parseColor("#FF9800"),  // Pending   - orange
                Color.parseColor("#F44336")   // Overdue   - red
        );
        dataSet.setValueTextSize(11f);
        dataSet.setValueTextColor(Color.WHITE);

        PieChart chart = binding.pieChartPayments;
        chart.setData(new PieData(dataSet));
        chart.getDescription().setEnabled(false);
        chart.setHoleRadius(40f);
        chart.setTransparentCircleRadius(45f);
        chart.setCenterText("Payments");
        chart.animateY(1000);
        chart.invalidate();
    }

    // ─── Target Progress Bar ──────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void updateTargetProgress(Map<String, Object> data) {
        Map<String, Object> target = (Map<String, Object>) data.get("current_target");
        if (target == null) return;

        double targetAmount   = target.containsKey("target_amount")   ? (Double) target.get("target_amount")   : 0;
        double achievedAmount = target.containsKey("achieved_amount") ? (Double) target.get("achieved_amount") : 0;
        int    progress       = targetAmount > 0 ? (int) ((achievedAmount / targetAmount) * 100) : 0;

        binding.progressTarget.setProgress(Math.min(progress, 100));
        binding.tvTargetProgress.setText(progress + "% of " + CurrencyUtils.format(targetAmount));
        binding.tvTargetAchieved.setText("Achieved: " + CurrencyUtils.format(achievedAmount));
    }

    // ─── Initial Chart Setup ──────────────────────────────────────────────────
    private void setupCharts() {
        // Bar Chart defaults
        binding.barChartCollections.getXAxis().setDrawGridLines(false);
        binding.barChartCollections.getAxisRight().setEnabled(false);
        binding.barChartCollections.getLegend().setEnabled(true);

        // Pie Chart defaults
        binding.pieChartPayments.setUsePercentValues(true);
        binding.pieChartPayments.getLegend().setEnabled(true);
    }
}
