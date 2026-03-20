package com.example.datafill.controller;

import com.example.datafill.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService userService;

    /**
     * Verify if a user is registered in the organization table.
     * 
     * @param userEmail The email to verify
     * @return A map with the registration status
     */
    @GetMapping("/verify")
    public Map<String, Object> verifyUser(@RequestParam String userEmail) {
        boolean registered = userService.isUserRegistered(userEmail);
        return Map.of("registered", registered);
    }

    @GetMapping("/list")
    public java.util.List<String> listUsers() {
        return userService.getAllUserEmails();
    }

    /**
     * 帆软 SSO 入口（直接放帆软目录链接用）
     * 帆软目录链接设为: http://121.40.224.201:8080/api/user/fr-redirect?fine_auth_token=${fine_auth_token}
     * 或不带 token 直接访问也行，会尝试从 cookie 中读取
     */
    @GetMapping("/fr-redirect")
    public void fineReportRedirect(
            @RequestParam(required = false) String fine_auth_token,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws Exception {

        // 1. 优先从 URL 参数获取 token，否则从 cookie 获取
        String token = fine_auth_token;
        if ((token == null || token.isBlank()) && request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if ("fine_auth_token".equals(c.getName())) {
                    token = c.getValue();
                    break;
                }
            }
        }

        String username = null;
        if (token != null && !token.isBlank()) {
            username = callFineReportApi(token);
        }

        // 2. 重定向到前端，带上用户名
        if (username != null) {
            // 简单 base64 编码（和前端 crypto.js 一致: u_ + btoa(encodeURIComponent(user))）
            String encoded = "u_" + java.util.Base64.getEncoder().encodeToString(
                    java.net.URLEncoder.encode(username, "UTF-8").getBytes());
            response.sendRedirect("/#/tasks?user=" + encoded);
        } else {
            // token 无效时，跳转到前端（会显示登录页）
            response.sendRedirect("/#/tasks");
        }
    }

    /**
     * 前端调用：通过 fine_auth_token 验证帆软身份
     */
    @GetMapping("/sso")
    public Map<String, Object> ssoVerify(@RequestParam String fine_auth_token) {
        String username = callFineReportApi(fine_auth_token);
        if (username != null) {
            return Map.of("success", true, "username", username);
        }
        return Map.of("success", false);
    }

    /**
     * 调帆软 API 验证 token 并获取用户名
     */
    private String callFineReportApi(String token) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                            "http://8.136.2.153:8081/webroot/decision/login/status/check?fine_auth_token="
                            + java.net.URLEncoder.encode(token, "UTF-8")))
                    .header("Cookie", "fine_auth_token=" + token)
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                // 帆软返回格式可能是 {"data":{"username":"xxx"}} 或直接 {"username":"xxx"}
                if (node.has("data") && node.get("data").has("username")) {
                    return node.get("data").get("username").asText();
                }
                if (node.has("username")) {
                    return node.get("username").asText();
                }
                if (node.has("userName")) {
                    return node.get("userName").asText();
                }
            }
        } catch (Exception e) {
            // 网络异常等
        }
        return null;
    }
}
