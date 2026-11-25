package com.bugtracker.controller;

import com.bugtracker.dto.ReportResponse;
import com.bugtracker.service.ReportPdfService;
import com.bugtracker.service.ReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reports over the bugs the current user can access. weeks is 4-52; projectId limits to one project. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportPdfService pdfService;

    public ReportController(ReportService reportService, ReportPdfService pdfService) {
        this.reportService = reportService;
        this.pdfService = pdfService;
    }

    @GetMapping
    public ReportResponse report(@RequestParam(defaultValue = "12") int weeks,
                                 @RequestParam(required = false) Long projectId) {
        return reportService.report(weeks, projectId);
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam(defaultValue = "12") int weeks,
                                      @RequestParam(required = false) Long projectId) {
        ReportResponse report = reportService.report(weeks, projectId);
        String filename = "bugtrack-report-" + report.to() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdfService.render(report));
    }
}
