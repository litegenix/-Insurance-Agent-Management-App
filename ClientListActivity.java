package com.insuranceagent.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.insuranceagent.adapters.ClientAdapter;
import com.insuranceagent.api.ApiClient;
import com.insuranceagent.api.ApiService;
import com.insuranceagent.databinding.ActivityClientListBinding;
import com.insuranceagent.models.Client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ClientListActivity extends AppCompatActivity {

    private ActivityClientListBinding binding;
    private ApiService                apiService;
    private ClientAdapter             adapter;
    private List<Client>              clientList = new ArrayList<>();

    // Debounce search to avoid calling API on every keystroke
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>             pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding    = ActivityClientListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        apiService = ApiClient.getService(this);
        setupRecyclerView();
        setupSearch();

        binding.fabAddClient.setOnClickListener(v ->
                startActivity(new Intent(this, AddClientActivity.class)));

        binding.swipeRefresh.setOnRefreshListener(this::loadClients);

        loadClients();
    }

    private void setupRecyclerView() {
        adapter = new ClientAdapter(clientList, client -> {
            // On client click → open detail screen
            Intent intent = new Intent(this, ClientDetailActivity.class);
            intent.putExtra("client_id", client.getId());
            intent.putExtra("client_name", client.getName());
            startActivity(intent);
        });
        binding.rvClients.setLayoutManager(new LinearLayoutManager(this));
        binding.rvClients.setAdapter(adapter);
    }

    // ─── Debounced Search ─────────────────────────────────────────────────────
    private void setupSearch() {
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pendingSearch != null) pendingSearch.cancel(false);

                String query = s.toString().trim();
                if (query.isEmpty()) {
                    loadClients();
                } else {
                    // Wait 400ms before firing search (debounce)
                    pendingSearch = scheduler.schedule(
                            () -> runOnUiThread(() -> searchClients(query)),
                            400, TimeUnit.MILLISECONDS
                    );
                }
            }
        });

        // Search filter chips: Name, CNIC, Phone, Policy Number
        binding.chipName.setOnCheckedChangeListener((btn, checked) ->
                binding.etSearch.setHint(checked ? "Search by Name" : "Search clients..."));
        binding.chipCnic.setOnCheckedChangeListener((btn, checked) ->
                binding.etSearch.setHint(checked ? "Search by CNIC (e.g. 35202-1234567-1)" : "Search clients..."));
        binding.chipPhone.setOnCheckedChangeListener((btn, checked) ->
                binding.etSearch.setHint(checked ? "Search by Phone" : "Search clients..."));
        binding.chipPolicy.setOnCheckedChangeListener((btn, checked) ->
                binding.etSearch.setHint(checked ? "Search by Policy Number" : "Search clients..."));
    }

    // ─── Load All Clients ─────────────────────────────────────────────────────
    private void loadClients() {
        binding.progressBar.setVisibility(View.VISIBLE);

        apiService.getClients(1, 50).enqueue(new Callback<List<Client>>() {
            @Override
            public void onResponse(Call<List<Client>> call, Response<List<Client>> response) {
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);

                if (response.isSuccessful() && response.body() != null) {
                    clientList.clear();
                    clientList.addAll(response.body());
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                }
            }

            @Override
            public void onFailure(Call<List<Client>> call, Throwable t) {
                binding.progressBar.setVisibility(View.GONE);
                binding.swipeRefresh.setRefreshing(false);
                Toast.makeText(ClientListActivity.this,
                        "Failed to load clients", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─── Search Clients ───────────────────────────────────────────────────────
    private void searchClients(String query) {
        binding.progressBar.setVisibility(View.VISIBLE);

        apiService.searchClients(query).enqueue(new Callback<List<Client>>() {
            @Override
            public void onResponse(Call<List<Client>> call, Response<List<Client>> response) {
                binding.progressBar.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null) {
                    clientList.clear();
                    clientList.addAll(response.body());
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                }
            }

            @Override
            public void onFailure(Call<List<Client>> call, Throwable t) {
                binding.progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void updateEmptyState() {
        if (clientList.isEmpty()) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            binding.rvClients.setVisibility(View.GONE);
        } else {
            binding.layoutEmpty.setVisibility(View.GONE);
            binding.rvClients.setVisibility(View.VISIBLE);
        }
        binding.tvClientCount.setText(clientList.size() + " client(s)");
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadClients(); // Refresh after returning from AddClient
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scheduler.shutdownNow();
    }
}
