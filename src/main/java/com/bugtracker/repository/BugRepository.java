package com.bugtracker.repository;

import com.bugtracker.entity.Bug;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface BugRepository extends JpaRepository<Bug, Long>, JpaSpecificationExecutor<Bug> {

    /** Loads the related project and users in the same query so listing bugs avoids N+1 selects. */
    @Override
    @EntityGraph(attributePaths = {"project", "reportedBy", "assignedTo"})
    List<Bug> findAll(Specification<Bug> spec, Sort sort);
}
