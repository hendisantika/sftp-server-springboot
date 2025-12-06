package com.sftp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
public class SecurityController {

    @GetMapping("/home")
    public String homePage(Model model, Principal principal) {
        if (principal != null) {
            model.addAttribute("username", principal.getName());
        }
        model.addAttribute("message", "Welcome to the SFTP File Manager!");
        return "home";
    }

    @GetMapping({"/", "/login"})
    public String loginPage(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "expired", required = false) String expired,
            Model model) {

        if (error != null) {
            model.addAttribute("errorMessage", "Invalid username or password.");
        }
        if (logout != null) {
            model.addAttribute("logoutMessage", "You have been logged out successfully.");
        }
        if (expired != null) {
            model.addAttribute("expiredMessage", "Your session has expired. Please log in again.");
        }

        return "login";
    }

    @GetMapping("/secured")
    public String securedPage(Model model, Principal principal) {
        if (principal != null) {
            model.addAttribute("username", principal.getName());
        }
        model.addAttribute("message", "This is a secured page. Only authenticated users can see this.");
        return "secured";
    }
}
