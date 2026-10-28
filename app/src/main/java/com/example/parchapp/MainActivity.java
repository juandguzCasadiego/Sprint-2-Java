package com.example.parchapp;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.data.AuthRepository;
import com.example.parchapp.data.Repositories;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.GoogleSignInFlow;

public class MainActivity extends AppCompatActivity {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final AuthRepository auth = Repositories.auth();
    private EditText emailInput;
    private EditText passwordInput;
    private Button signInButton;
    private GoogleSignInFlow googleSignIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button continueButton = findViewById(R.id.continue_button);
        View googleButton = findViewById(R.id.google_button);
        signInButton = findViewById(R.id.sign_in_button);
        emailInput = findViewById(R.id.email_input);
        passwordInput = findViewById(R.id.password_input);
        googleSignIn = new GoogleSignInFlow(this, new OpenDashboardCallback());

        // Returning users skip the form cause they have an account: Firebase restored their session in ParchApplication.
        UserProfile restored = auth.getSignedInUser();
        if (restored != null) {
            findViewById(R.id.restored_session_card).setVisibility(View.VISIBLE);
            ((android.widget.TextView) findViewById(R.id.restored_user_name)).setText(restored.getFirstName());
            ((android.widget.TextView) findViewById(R.id.restored_user_initials)).setText(restored.getInitials());
            bindProfileDetails();
            ((android.widget.TextView) findViewById(R.id.restored_user_email)).setText(restored.getEmail());
            continueButton.setText("Continue as " + restored.getFirstName());
            continueButton.setOnClickListener(v -> openDashboard(restored));
        } else if (Repositories.isFirebaseAvailable()) {
            findViewById(R.id.restored_session_card).setVisibility(View.GONE);
            continueButton.setOnClickListener(v -> {
                showMessage("Sign in with your university email to continue");
                emailInput.requestFocus();
            });
        } else {
            findViewById(R.id.restored_session_card).setVisibility(View.GONE);
            continueButton.setOnClickListener(v -> openDashboard(UserSession.demoUser()));
        }

        googleButton.setOnClickListener(v -> googleSignIn.start());
        signInButton.setOnClickListener(v -> submit(false));
        findViewById(R.id.create_account).setOnClickListener(v ->
                startActivity(new Intent(this, CreateAccountActivity.class)));
        findViewById(R.id.forgot_password).setOnClickListener(v -> sendPasswordReset());
    }

    private void submit(boolean register) {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (!isValidEmail(email)) {
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            passwordInput.setError("At least " + MIN_PASSWORD_LENGTH + " characters");
            return;
        }

        signInButton.setEnabled(false);
        Callback<UserProfile> callback = new Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile user) {
                signInButton.setEnabled(true);
                passwordInput.setText("");
                openDashboard(user);
            }

            @Override
            public void onError(Exception error) {
                signInButton.setEnabled(true);
                showMessage(register
                        ? "Could not create the account: " + error.getLocalizedMessage()
                        : "Email or password is incorrect");
            }
        };
        if (register) {
            auth.register("", email, password, callback);
        } else {
            auth.signIn(email, password, callback);
        }
    }

    private void sendPasswordReset() {
        String email = emailInput.getText().toString().trim();
        if (!isValidEmail(email)) {
            return;
        }
        auth.sendPasswordReset(email, new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                showMessage("Password reset email sent to " + email);
            }

            @Override
            public void onError(Exception error) {
                showMessage("Could not send the reset email");
            }
        });
    }

    private final class OpenDashboardCallback implements Callback<UserProfile> {
        @Override
        public void onSuccess(UserProfile user) {
            openDashboard(user);
        }

        @Override
        public void onError(Exception error) {
            showMessage("Google sign-in failed");
        }
    }

    private boolean isValidEmail(String email) {
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Enter your university email");
            emailInput.requestFocus();
            return false;
        }
        return true;
    }

    private void openDashboard(UserProfile user) {
        UserSession.setCurrentUser(user);
        AnalyticsTracker.getInstance().setUserId(user.getId());
        startActivity(new Intent(this, DashboardActivity.class));
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /* Program and verification come from the backend profile (getProfile); hidden until known. */
    private void bindProfileDetails() {
        android.widget.TextView program = findViewById(R.id.restored_user_program);
        View verified = findViewById(R.id.restored_user_verified);
        program.setVisibility(View.GONE);
        verified.setVisibility(View.GONE);
        Repositories.profiles().getProfile(new Callback<com.example.parchapp.data.ProfileRepository.Profile>() {
            @Override
            public void onSuccess(com.example.parchapp.data.ProfileRepository.Profile profile) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                program.setText(profile.program);
                program.setVisibility(profile.program.isEmpty() ? View.GONE : View.VISIBLE);
                verified.setVisibility(profile.verified ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(Exception error) {
                // the card still works with the name and email from the session
            }
        });
    }
}
