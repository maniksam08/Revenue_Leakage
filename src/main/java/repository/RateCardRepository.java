package repository;

import entity.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RateCardRepository extends JpaRepository<RateCard, Long> {

    List<RateCard> findByChannelOrCourierNameOrderByWeightSlabLimitGAsc(String name);

    /** Smallest contracted slab that covers the given weight; empty means weight exceeds all slabs. */
    @Query("""
           select r from RateCard r
           where r.channelOrCourierName = :name and r.weightSlabLimitG >= :weightG
           order by r.weightSlabLimitG asc
           limit 1
           """)
    Optional<RateCard> findApplicableSlab(@Param("name") String name, @Param("weightG") int weightG);

    /** Highest slab, used as the base when computing extra_per_500g for heavier parcels. */
    @Query("""
           select r from RateCard r
           where r.channelOrCourierName = :name
           order by r.weightSlabLimitG desc
           limit 1
           """)
    Optional<RateCard> findTopSlab(@Param("name") String name);
}
