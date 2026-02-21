package com.insuranceagent.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.insuranceagent.api.ApiClient;
import com.insuranceagent.api.ApiService;
import com.insuranceagent.databinding.ActivityLoginBinding;
import com.insuranceagent.utils.SessionManager;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseAuth          firebaseAuth;
    private ApiService            apiService;
    private SessionManager        sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding        = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        firebaseAuth   = FirebaseAuth.getInstance();
        apiService     = ApiClient.getService(this);
        sessionManager = new SessionManager(this);

        // Skip login if already authenticated
        if (sessionManager.isLoggedIn()) {
            goToMain();
            return;
        }

        binding.btnLogin.setOnClickListener(v -> attemptLogin());
        binding.btnRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        binding.tvForgotPassword.setOnClickListener(v -> sendPasswordReset());
    }

    private void attemptLogin() {
        String email    = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        // Validate
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.setError("Enter a valid email"); return;
        }
        if (TextUtils.isEmpty(password) || password.length() < 6) {
            binding.etPassword.setError("Password must be 6+ characters"); return;
        }

        setLoading(true);

        // Step 1: Sign in with Firebase
        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    // Step 2: Get Firebase ID token
                    authResult.getUser().getIdToken(false)
                            .addOnSuccessListener(tokenResult -> {
                                String firebaseToken = tokenResult.getToken();
                                // Step 3: Exchange Firebase token for our backend JWT
                                loginWithBackend(email, password, firebaseToken);
                            })
                            .addOnFailureListener(e -> {
                                setLoading(false);
                                Toast.makeText(this, "Token error: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    String msg = "Login failed";
                    if (e.getMessage() != null) {
                        if (e.getMessage().contains("no user record")) msg = "Account not found";
                        else if (e.getMessage().contains("password"))  msg = "Incorrect password";
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
    }

    @SuppressWarnings("unchecked")
    private void loginWithBackend(String email, String password, String firebaseToken) {
        Map<String, String> body = new HashMap<>();
        body.put("email",          email);
        body.put("password",       password);
        body.put("firebase_token", firebaseToken);

        apiService.login(body).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call,
                                   Response<Map<String, Object>> response) {
                setLoading(false);
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> data = response.body();
                    String jwtToken = (String) data.get("token");
                    ApiClient.saveToken(LoginActivity.this, jwtToken);

                    // Save agent info to session
                    Map<String, Object> agent = (Map<String, Object>) data.get("agent");
                    if (agent != null) {
                        sessionManager.saveAgent(
                                (String) agent.get("id"),
                                (String) agent.get("name"),
                                (String) agent.get("email"),
                                (String) agent.get("agentCode")
                        );
                    }
                    goToMain();
                } else {
                    Toast.makeText(LoginActivity.this,
                            "Backend authentication failed", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                setLoading(false);
                Toast.makeText(LoginActivity.this,
                        "Network error: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void sendPasswordReset() {
        String email = binding.etEmail.getText().toString().trim();
        if (TextUtils.isEmpty(email)) {
            binding.etEmail.setError("Enter your email first");
            return;
        }
        firebaseAuth.sendPasswordResetEmail(email)
                .addOnSuccessListener(v ->
                        Toast.makeText(this, "Reset email sent to " + email,
                                Toast.LENGTH_LONG).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnLogin.setEnabled(!loading);
        binding.btnLogin.setText(loading ? "Signing in..." : "Login");
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
