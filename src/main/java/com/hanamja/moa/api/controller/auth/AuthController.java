package com.hanamja.moa.api.controller.auth;

import com.hanamja.moa.api.dto.auth.request.LoginRequestDto;
import com.hanamja.moa.api.dto.auth.request.OnBoardingRequestDto;
import com.hanamja.moa.api.dto.auth.request.RegenerateAccessTokenRequestDto;
import com.hanamja.moa.api.dto.auth.response.LoginResponseDto;
import com.hanamja.moa.api.dto.auth.response.RegenerateAccessTokenResponseDto;
import com.hanamja.moa.api.dto.user.response.UserInfoResponseDto;
import com.hanamja.moa.api.entity.user.UserAccount.UserAccount;
import com.hanamja.moa.api.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@Tag(name = "auth", description = "인증 관련 API")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@RequestBody LoginRequestDto loginRequestDto) {
        LoginResponseDto responseDto = authService.login(loginRequestDto);

        return ResponseEntity.ok(responseDto);
    }


    @Operation(summary = "내 정보 조회")
    @GetMapping(value = "/info")
    public ResponseEntity<?> myInfo(@Parameter(hidden = true) @AuthenticationPrincipal UserAccount userAccount) {
        String studentId = userAccount.getStudentId();
        return ResponseEntity.ok().body(studentId);
    }

    @Operation(summary = "온보딩")
    @PutMapping("/on-boarding")
    public ResponseEntity<UserInfoResponseDto> onBoardUser(@Parameter(hidden = true) @AuthenticationPrincipal UserAccount userAccount, @RequestBody OnBoardingRequestDto onBoardingRequestDto) {

        return ResponseEntity.ok(authService.onBoardUser(userAccount, onBoardingRequestDto));
    }


    @Operation(summary = "토큰 재발급")
    @PostMapping("/regenerate-access-token")
    public ResponseEntity<RegenerateAccessTokenResponseDto> regenerateAccessToken(
            @RequestBody RegenerateAccessTokenRequestDto requestDto
    ) {
        RegenerateAccessTokenResponseDto responseDto = authService.regenerateAccessToken(requestDto);

        return ResponseEntity.ok(responseDto);
    }
}
