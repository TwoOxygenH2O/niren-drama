import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class VerifyBCrypt {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = "$2a$10$7JEoNP8gqGBvN8EeJ5b6gO7ZGxMmCKLNVIPz3fvKFjMMfE6LV9bHO";
        String password = "admin123";
        System.out.println(encoder.matches(password, hash));
    }
}