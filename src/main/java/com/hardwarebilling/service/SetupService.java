package com.hardwarebilling.service;

import com.hardwarebilling.config.AppProperties;
import com.hardwarebilling.repo.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * First-time setup without default credentials. While no user exists, /setup is open but requires a
 * one-time token: either SETUP_TOKEN from the environment, or a random one printed once in the
 * server console at startup. After the administrator is created, /setup disappears.
 */
@Service
public class SetupService {
    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final AppUserRepository users;
    private final CompanyService company;
    private final UserService userService;
    private final String token;
    private volatile boolean done;

    public SetupService(AppUserRepository users, CompanyService company, UserService userService, AppProperties props) {
        this.users = users;
        this.company = company;
        this.userService = userService;
        this.token = props.setupToken() != null && !props.setupToken().isBlank() ? props.setupToken() : randomToken();
    }

    private static String randomToken() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public boolean needsSetup() {
        if (done) return false;
        boolean needs = users.count() == 0;
        if (!needs) done = true;
        return needs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        if (needsSetup()) {
            log.warn("=====================================================================");
            log.warn(" FIRST-TIME SETUP REQUIRED. Open  /setup  in your browser and enter");
            log.warn(" this one-time setup token:  {}", token);
            log.warn(" (the token stops working as soon as the administrator is created)");
            log.warn("=====================================================================");
        }
    }

    public boolean tokenMatches(String given) {
        if (given == null) return false;
        return MessageDigest.isEqual(given.trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public void complete(String givenToken, CompanyService.CompanyForm companyForm,
                                      String adminUser, String adminName, String password) {
        if (!needsSetup()) throw new BusinessException("Setup is already complete");
        if (!tokenMatches(givenToken)) throw new BusinessException("Invalid setup token");
        if (!company.exists()) company.create(companyForm);
        userService.createInitialAdmin(adminUser, adminName, password);
        done = true;
    }
}
