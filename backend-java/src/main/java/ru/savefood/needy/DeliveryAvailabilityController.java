package ru.savefood.needy;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.savefood.security.Auth;
import ru.savefood.security.Authz;
import ru.savefood.security.CurrentUser;

@RestController
public class DeliveryAvailabilityController {
    private final DeliveryAvailabilityService service;

    public DeliveryAvailabilityController(DeliveryAvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/needy/{needyId}/ticket/{ticketId}/delivery-availability")
    public Map<String, Object> availability(@PathVariable int needyId, @PathVariable int ticketId,
            @Auth CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "needy", needyId);
        return service.forTicket(needyId, ticketId);
    }
}
