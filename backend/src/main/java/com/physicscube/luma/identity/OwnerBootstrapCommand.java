package com.physicscube.luma.identity;

import java.io.PrintStream;
import java.util.function.IntConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Operator command, run with scripts/bootstrap-owner.sh (or the jar and
 * {@code --luma.bootstrap-owner.email=...}): creates the academy if needed and its first
 * owner, issues an activation invitation, prints or emails the link, and exits. Not part
 * of a running server: the script starts the jar without a web server.
 *
 * <p>The printed link is a credential for 72 hours. It goes to standard output only, never
 * to the log, so run the command in a terminal, not where output is collected; or use
 * email delivery.
 */
@Component
@ConditionalOnProperty("luma.bootstrap-owner.email")
class OwnerBootstrapCommand implements ApplicationRunner {
    private final OwnerBootstrapService bootstrap;
    private final MailProperties mail;
    private final AccountMailer mailer;
    private final String academyName;
    private final String ownerEmail;
    private final String delivery;
    private final String printOrigin;
    private final PrintStream out;
    private final IntConsumer exit;

    /** The constructor Spring uses; the other one lets tests capture output and the exit code. */
    @Autowired
    OwnerBootstrapCommand(OwnerBootstrapService bootstrap, MailProperties mail, AccountMailer mailer,
                          ConfigurableApplicationContext context,
                          @Value("${luma.bootstrap-owner.academy-name:}") String academyName,
                          @Value("${luma.bootstrap-owner.email}") String ownerEmail,
                          @Value("${luma.bootstrap-owner.delivery:PRINT}") String delivery,
                          @Value("${luma.bootstrap-owner.web-origin:}") String printOrigin) {
        this(bootstrap, mail, mailer, academyName, ownerEmail, delivery, printOrigin, System.out,
                code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    OwnerBootstrapCommand(OwnerBootstrapService bootstrap, MailProperties mail, AccountMailer mailer, String academyName,
                          String ownerEmail, String delivery, String printOrigin, PrintStream out, IntConsumer exit) {
        this.bootstrap = bootstrap;
        this.mail = mail;
        this.mailer = mailer;
        this.academyName = academyName;
        this.ownerEmail = ownerEmail;
        this.delivery = delivery;
        this.printOrigin = printOrigin;
        this.out = out;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        exit.accept(execute());
    }

    /** @return the process exit code */
    int execute() {
        boolean email = "EMAIL".equalsIgnoreCase(delivery);
        if (!email && !"PRINT".equalsIgnoreCase(delivery)) {
            out.println("LUMA owner bootstrap refused: delivery must be PRINT or EMAIL.");
            return 2;
        }
        if (email && !mail.enabled()) {
            out.println("LUMA owner bootstrap refused: email is not configured (LUMA_MAIL_ENABLED); use PRINT instead.");
            return 2;
        }
        String origin;
        try {
            origin = email ? mail.webOrigin()
                    : !printOrigin.isBlank() ? MailProperties.origin(printOrigin) : mail.enabled() ? mail.webOrigin() : null;
        } catch (IllegalArgumentException e) {
            out.println("LUMA owner bootstrap refused: the web origin must be https (http only for localhost).");
            return 2;
        }
        OwnerBootstrapService.Result result;
        try {
            result = bootstrap.bootstrap(academyName, ownerEmail);
        } catch (OwnerBootstrapService.RefusedException e) {
            out.println("LUMA owner bootstrap refused: " + e.getMessage());
            return 1;
        }
        String link = (origin == null ? "" : origin) + "/activate#token=" + result.token();
        out.println("LUMA owner bootstrap: academy " + result.academyId() + (result.academyCreated() ? " created" : " found")
                + "; owner account " + result.ownerId() + (result.ownerCreated() ? " created" : " already pending, new invitation issued")
                + "; the invitation expires " + result.expiresAt() + ".");
        if (email) {
            try {
                // Synchronously: the process exits right after.
                mailer.invitation(ownerEmail.strip().toLowerCase(java.util.Locale.ROOT), link, result.expiresAt());
            } catch (RuntimeException e) {
                out.println("The invitation was saved but the email could not be sent (" + e.getClass().getSimpleName()
                        + "). Run the command again with PRINT, or fix email and run it again.");
                return 3;
            }
            out.println("Invitation emailed to the owner.");
        } else {
            out.println("Send this link to the owner privately. It works once and replaces any earlier link:");
            out.println(link);
            if (origin == null) {
                out.println("(Put the web app's origin, such as https://luma.example, in front of the path above.)");
            }
        }
        return 0;
    }
}
