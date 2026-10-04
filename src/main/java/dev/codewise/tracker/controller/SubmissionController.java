package dev.codewise.tracker.controller;

import dev.codewise.tracker.dto.ImportSubmissionRequest;
import dev.codewise.tracker.dto.ImportSubmissionResponse;
import dev.codewise.tracker.dto.MonthlyReportResponse;
import dev.codewise.tracker.dto.QuestionStatsResponse;
import dev.codewise.tracker.dto.ReportCardResponse;
import dev.codewise.tracker.dto.SubmissionResponse;
import dev.codewise.tracker.service.MonthlyCardService;
import dev.codewise.tracker.service.SubmissionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "trackerApiKey")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final MonthlyCardService monthlyCardService;

    public SubmissionController(
            SubmissionService submissionService,
            MonthlyCardService monthlyCardService
    ) {
        this.submissionService = submissionService;
        this.monthlyCardService = monthlyCardService;
    }

    @PostMapping("/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportSubmissionResponse importSubmission(
            @Valid @RequestBody ImportSubmissionRequest request
    ) {
        return submissionService.importSubmission(request);
    }

    @GetMapping("/stats/questions")
    public List<QuestionStatsResponse> getQuestionStats() {
        return submissionService.getQuestionStats();
    }

    @GetMapping("/stats/monthly")
    public MonthlyReportResponse getMonthlyReport(
            @RequestParam int year,
            @RequestParam int month
    ) {
        return submissionService.getMonthlyReport(year, month);
    }

    @GetMapping("/questions/{problemKey}/submissions")
    public List<SubmissionResponse> getQuestionSubmissions(
            @PathVariable String problemKey
    ) {
        return submissionService.getQuestionSubmissions(problemKey);
    }

    /** 重新生成指定月份的手账风分享卡片 PNG。 */
    @PostMapping("/report/card/{year}/{month}")
    public ReportCardResponse generateMonthlyCard(
            @PathVariable int year,
            @PathVariable int month
    ) throws IOException {
        MonthlyCardService.CardFile card = monthlyCardService.renderMonthlyCard(year, month);
        return new ReportCardResponse(card.fileName(), card.year(), card.month());
    }

    /** 下载月报卡片 PNG，文件不存在时会先自动生成。 */
    @GetMapping("/report/card/{year}/{month}/png")
    public ResponseEntity<FileSystemResource> downloadMonthlyCard(
            @PathVariable int year,
            @PathVariable int month
    ) throws IOException {
        MonthlyCardService.CardFile card = monthlyCardService.generateMonthlyCard(year, month);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + card.fileName() + "\"")
                .body(new FileSystemResource(card.path()));
    }
}
