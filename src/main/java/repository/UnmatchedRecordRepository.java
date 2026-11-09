package repository;

import entity.UnmatchedRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnmatchedRecordRepository extends JpaRepository<UnmatchedRecord, Long> {

    Page<UnmatchedRecord> findByStatus(UnmatchedRecord.ReviewStatus status, Pageable pageable);

    Page<UnmatchedRecord> findByStatusAndReason(UnmatchedRecord.ReviewStatus status,
                                                UnmatchedRecord.Reason reason, Pageable pageable);

    java.util.List<UnmatchedRecord> findByOrderIdInAndReasonAndStatus(java.util.Collection<String> orderIds,
                                                                      UnmatchedRecord.Reason reason,
                                                                      UnmatchedRecord.ReviewStatus status);
}
