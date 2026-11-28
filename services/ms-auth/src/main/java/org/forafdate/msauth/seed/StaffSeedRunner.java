package org.forafdate.msauth.seed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StaffSeedRunner implements ApplicationRunner {

    private final StaffAccountSeeder seeder;
    private final boolean enabled;
    private final String adminPassword;
    private final String receptionistPassword;
    private final String ophthalmologistPassword;

    public StaffSeedRunner(
            StaffAccountSeeder seeder,
            @Value("${auth.seed.enabled:false}") boolean enabled,
            @Value("${auth.seed.admin-password:}") String adminPassword,
            @Value("${auth.seed.receptionist-password:}") String receptionistPassword,
            @Value("${auth.seed.ophthalmologist-password:}") String ophthalmologistPassword
    ) {
        this.seeder = seeder;
        this.enabled = enabled;
        this.adminPassword = adminPassword;
        this.receptionistPassword = receptionistPassword;
        this.ophthalmologistPassword = ophthalmologistPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        seeder.seed(new StaffAccountSeeder.Credentials(
                adminPassword,
                receptionistPassword,
                ophthalmologistPassword
        ));
    }
}
