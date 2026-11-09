package controller;

import enums.ReportType;
import ingest.ApproveMappingRequest;
import ingest.MappingProfileService;
import ingest.ReportDtos.MappingProfileResponse;
import ingest.ReportDtos.UploadPreviewResponse;
import ingest.ReportUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportUploadService uploadService;
    private final MappingProfileService profileService;

    /** Step 1: upload -> preview. Nothing is stored. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadPreviewResponse upload(@RequestPart("file") MultipartFile file,
                                        @RequestParam("partnerId") String partnerId,
                                        @RequestParam(value = "reportType", required = false) String reportType) {
        return uploadService.preview(file, partnerId, parseType(reportType));
    }

    /** Step 2: the user confirms (and possibly edits) the suggested mapping -> saved for this partner. */
    @PostMapping("/mapping-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public MappingProfileResponse approve(@Valid @RequestBody ApproveMappingRequest req, Principal principal) {
        int preamble = req.preambleRows() == null ? 0 : req.preambleRows();
        return MappingProfileResponse.from(profileService.approve(
                req.partnerId(), req.reportType(), req.mappings(), preamble,
                principal == null ? null : principal.getName()));
    }

    @GetMapping("/mapping-profiles/{partnerId}")
    public MappingProfileResponse active(@PathVariable String partnerId, @RequestParam("reportType") String reportType) {
        ReportType type = parseType(reportType);
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reportType is required");
        }
        return profileService.findActive(partnerId, type).map(MappingProfileResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved mapping profile"));
    }

    @GetMapping("/mapping-profiles/{partnerId}/history")
    public List<MappingProfileResponse> history(@PathVariable String partnerId, @RequestParam("reportType") String reportType) {
        ReportType type = parseType(reportType);
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reportType is required");
        }
        return profileService.history(partnerId, type).stream().map(MappingProfileResponse::from).toList();
    }

    private static ReportType parseType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ReportType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reportType must be SETTLEMENT or FREIGHT");
        }
    }
}
