package repository;

import entity.MappingProfile;
import enums.ReportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MappingProfileRepository extends JpaRepository<MappingProfile, Long> {

    Optional<MappingProfile> findFirstByPartnerIdAndReportTypeAndActiveTrue(String partnerId, ReportType reportType);

    List<MappingProfile> findByPartnerIdAndReportTypeOrderByVersionDesc(String partnerId, ReportType reportType);

    @Query("select coalesce(max(p.version), 0) from MappingProfile p " +
           "where p.partnerId = :partnerId and p.reportType = :type")
    int maxVersion(@Param("partnerId") String partnerId, @Param("type") ReportType type);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MappingProfile p set p.active = false " +
           "where p.partnerId = :partnerId and p.reportType = :type and p.active = true")
    int deactivateActive(@Param("partnerId") String partnerId, @Param("type") ReportType type);
}
