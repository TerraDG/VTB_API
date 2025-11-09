package com.hackathon.apiscanner.ui;

import com.hackathon.apiscanner.checks.BrokenAuthCheck;
import com.hackathon.apiscanner.core.ApiLoader;
import com.hackathon.apiscanner.core.ApiTester;
import com.hackathon.apiscanner.core.EndpointDataProvider;
import com.hackathon.apiscanner.core.AuthManager;
import com.hackathon.apiscanner.report.HtmlReportGenerator;
import com.hackathon.apiscanner.report.HtmlReportGenerator.EndpointResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;

public class Launcher extends JFrame {
    private final JTextField teamField = new JTextField("team178", 20);
    private final JPasswordField secretField = new JPasswordField("0RwXPj7naBAD68elrQ2W8IAn8KmcQkCq",20);
    private final JTextField openapiField = new JTextField("openapi.json", 30);
    private final JTextArea logArea = new JTextArea(20, 80);
    private final JButton runBtn = new JButton("Run scan");
    private final JButton saveBtn = new JButton("Save report");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private final String baseUrl = "https://vbank.open.bankingapi.ru";

    private List<HtmlReportGenerator.EndpointResult> lastResults = Collections.emptyList();
    private List<HtmlReportGenerator.EndpointResult> lastPreResults = Collections.emptyList();
    private List<com.hackathon.apiscanner.checks.BrokenAuthCheck.Result> lastAuthIssues = Collections.emptyList();
    private List<HtmlReportGenerator.EndpointResult> lastSecurityTests = Collections.emptyList();
    private long lastTotalMs = 0;

    public Launcher() {
        super("API Scanner Launcher");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        initUI();
        pack();
        setLocationRelativeTo(null);
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(8,8));
        root.setBorder(new EmptyBorder(8,8,8,8));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4,4,4,4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        top.add(new JLabel("Team ID:"), c);
        c.gridx = 1;
        top.add(teamField, c);
        teamField.setEditable(false);

        c.gridx = 0; c.gridy = 1;
        top.add(new JLabel("Client secret:"), c);
        c.gridx = 1;
        top.add(secretField, c);

        c.gridx = 0; c.gridy = 2;
        top.add(new JLabel("OpenAPI file:"), c);
        c.gridx = 1;
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fileRow.add(openapiField);
        JButton browse = new JButton("Browse");
        fileRow.add(browse);
        top.add(fileRow, c);

        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(new File("."));
            int r = fc.showOpenDialog(Launcher.this);
            if (r == JFileChooser.APPROVE_OPTION) {
                openapiField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // Buttons
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.add(runBtn);
        btns.add(saveBtn);
        saveBtn.setEnabled(false);

        runBtn.addActionListener(this::onRun);
        saveBtn.addActionListener(this::onSave);

        c.gridx = 0; c.gridy = 3; c.gridwidth = 2;
        top.add(btns, c);

        logArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(logArea);

        progressBar.setStringPainted(true);
        progressBar.setValue(0);

        // Bottom status
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(progressBar, BorderLayout.CENTER);

        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setStatus(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s));
    }

    private void setProgress(int value) {
        SwingUtilities.invokeLater(() -> progressBar.setValue(value));
    }

    private void onRun(ActionEvent ae) {
        String team = teamField.getText().trim();
        String secret = new String(secretField.getPassword()).trim();
        String openapi = openapiField.getText().trim();

        if (team.isEmpty() || secret.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите Team ID и Client secret", "Input required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        runBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        logArea.setText("");
        setStatus("Running scan...");
        setProgress(0);

        // Запускается в фоновом режиме, чтобы пользовательский интерфейс оставался активным
        new Thread(() -> {
            try {
                Instant allStart = Instant.now();
                appendLog("=== Launcher: starting scan ===");
                setProgress(5);
                appendLog("OpenAPI: " + openapi);
                appendLog("Base URL: " + baseUrl);
                appendLog("Team ID: " + team);

                // 1) Основной токен (AuthManager)
                appendLog("Requesting primary token...");
                String primaryToken = AuthManager.getAccessToken(baseUrl, team, secret);
                appendLog("Primary token result: " + (primaryToken == null ? "null" : ("***" + (primaryToken.length()>8 ? primaryToken.substring(primaryToken.length()-8) : primaryToken))));
                setProgress(20);

                // 2) Загрузка endpoints из OpenAPI
                appendLog("Loading endpoints from OpenAPI...");
                Map<String, List<String>> endpoints = ApiLoader.loadEndpoints(openapi);
                appendLog("Endpoints loaded: " + endpoints.size());
                setProgress(35);

                // 3) Подготовка EndpointDataProvider (будет запускать источники, настроенные в endpoint-data.json)
                appendLog("Loading endpoint-data config and executing sources...");
                EndpointDataProvider provider = new EndpointDataProvider("endpoint-data.json");
                Map<String, Map<String, Object>> resolved = provider.resolveAll(baseUrl, primaryToken);
                setProgress(50);

                // Попытка получить токен от источника
                String providerToken = provider.getSavedValue("/auth/bank-token?client_id={client_id}&client_secret={client_secret}", "/access_token");
                String usedToken = providerToken != null ? providerToken : primaryToken;
                appendLog("Using token (preview): " + (usedToken == null ? "null" : ("***" + (usedToken.length()>8 ? usedToken.substring(usedToken.length()-8) : usedToken))));
                setProgress(65);

                // 4) Запуск общих тестов проверки
                appendLog("Running generic endpoint tests...");
                Instant t0 = Instant.now();
                List<EndpointResult> genericResults = ApiTester.testEndpoints(baseUrl, usedToken, endpoints, resolved);
                Instant t1 = Instant.now();
                appendLog("Generic tests done: " + genericResults.size() + " checks");
                setProgress(80);

                // 5) Запуск Consent-specific тестов
                appendLog("Running consent-specific security tests...");
                List<EndpointResult> consentResults = ApiTester.runConsentEndpointTests(baseUrl, usedToken, team);
                appendLog("Consent tests done: " + consentResults.size() + " checks");
                setProgress(85);

                // 6) BrokenAuth check
                appendLog("Running BOLA/BrokenAuth check...");
                BrokenAuthCheck bcheck = new BrokenAuthCheck();
                List<BrokenAuthCheck.Result> bola = bcheck.run(baseUrl, new ArrayList<>(endpoints.keySet()));
                appendLog("BrokenAuth results: " + (bola == null ? 0 : bola.size()));
                setProgress(90);

                // Объединение результата
                List<EndpointResult> all = new ArrayList<>();
                all.addAll(genericResults);
                all.addAll(consentResults);
                setProgress(95);

                Instant allEnd = Instant.now();
                long totalMs = Duration.between(allStart, allEnd).toMillis();

                // Сохранение значений полей
                lastResults = all;
                lastPreResults = Collections.emptyList();
                lastAuthIssues = bola == null ? Collections.emptyList() : bola;
                lastSecurityTests = consentResults;
                lastTotalMs = totalMs;

                // compute ok/errors
                int ok = (int) all.stream().filter(r -> r.success).count();
                int errors = all.size() - ok;

                appendLog("Scan finished. Total checks: " + all.size() + " OK=" + ok + " ERR=" + errors);
                appendLog("Generating HTML report to temporary report.html ...");

                // генерация отчёта в файл
                HtmlReportGenerator.generateHtmlReport(
                        lastPreResults,
                        lastResults,
                        ok,
                        errors,
                        lastResults.size(),
                        totalMs,
                        lastAuthIssues,
                        lastSecurityTests
                );

                appendLog("Report generated: report.html (you can Save As into another location)");
                setProgress(100);
                setStatus("Done. report.html ready.");
                saveBtn.setEnabled(true);

            } catch (Exception ex) {
                appendLog("ERROR: " + ex.getMessage());
                ex.printStackTrace();
                setStatus("Error");
            } finally {
                runBtn.setEnabled(true);
            }
        }).start();
    }

    private void onSave(ActionEvent ae) {
        JFileChooser fc = new JFileChooser(new File("."));
        fc.setSelectedFile(new File("report.html"));
        int r = fc.showSaveDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        try {

            int ok = (int) lastResults.stream().filter(r2 -> r2.success).count();
            int errors = lastResults.size() - ok;
            HtmlReportGenerator.generateHtmlReport(
                    lastPreResults,
                    lastResults,
                    ok,
                    errors,
                    lastResults.size(),
                    lastTotalMs,
                    lastAuthIssues,
                    lastSecurityTests
            );

            File src = new File("report.html");

            if (src.exists()) {
                java.nio.file.Files.copy(src.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                appendLog("Saved report to: " + out.getAbsolutePath());
                JOptionPane.showMessageDialog(this, "Report saved: " + out.getAbsolutePath());
            } else {
                appendLog("No temporary report.html found — regenerating directly to chosen file");
                JOptionPane.showMessageDialog(this, "Saved (regenerated) to: " + out.getAbsolutePath());
            }
        } catch (Exception ex) {
            appendLog("Save error: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Launcher l = new Launcher();
            l.setVisible(true);
        });
    }
}
