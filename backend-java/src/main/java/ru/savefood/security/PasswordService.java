package ru.savefood.security;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
@Service
public class PasswordService {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public String hash(String raw) {
        return encoder.encode(raw);
    }
    /** A malformed/empty stored hash reads as "wrong password", never a crash. */
    public boolean verify(String raw, String hashed) {
        if (hashed == null || hashed.isEmpty()) {
            return false;
        }
        try {
            return encoder.matches(raw, hashed);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
