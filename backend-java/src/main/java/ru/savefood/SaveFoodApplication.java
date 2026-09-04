package ru.savefood;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class SaveFoodApplication {
    public static void main(String[] args) {
        SpringApplication.run(SaveFoodApplication.class, args);
    }
}
