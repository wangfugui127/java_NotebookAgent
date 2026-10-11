package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.api.dto.Dtos.UserSettingsUpdate;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    private final UserRepository users;

    public UsersController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/me/settings")
    public Map<String, Object> readSettings(@CurrentUser User user) {
        return Map.of("approval_mode", user.getApprovalMode());
    }

    @PutMapping("/me/settings")
    @Transactional
    public Map<String, Object> updateSettings(@Valid @RequestBody UserSettingsUpdate body, @CurrentUser User user) {
        user.setApprovalMode(body.approvalMode());
        users.save(user);
        return Map.of("approval_mode", user.getApprovalMode());
    }
}
