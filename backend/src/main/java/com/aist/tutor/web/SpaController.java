package com.aist.tutor.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * In the deployed build the React app is served from the backend's static resources.
 * Forward the client-side routes to index.html so a page refresh or shared link works.
 */
@Controller
public class SpaController {

    @GetMapping({"/login", "/signup", "/home", "/learn/{chapterId}"})
    public String forwardToApp() {
        return "forward:/index.html";
    }
}
