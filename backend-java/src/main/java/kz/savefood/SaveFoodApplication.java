package kz.savefood;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // drives background/MaintenanceTasks (gated by savefood.background-tasks)
public class SaveFoodApplication {
    public static void main(String[] args) {
        SpringApplication.run(SaveFoodApplication.class, args);
    }
}
