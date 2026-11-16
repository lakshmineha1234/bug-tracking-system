package com.bugtracker.service;

import com.bugtracker.dto.BugFilter;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.User;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Database queries for listing bugs. Mirrors BugPermissions.canView so lists only contain accessible bugs. */
public final class BugSpecifications {

    private BugSpecifications() {
    }

    public static Specification<Bug> visibleTo(User user) {
        return switch (user.getRole()) {
            case ADMIN -> (root, query, cb) -> cb.conjunction();
            case DEVELOPER -> (root, query, cb) -> cb.equal(root.get("assignedTo").get("id"), user.getId());
            case TESTER -> (root, query, cb) -> {
                Subquery<Long> memberProjects = query.subquery(Long.class);
                Root<Project> project = memberProjects.from(Project.class);
                Join<Project, User> member = project.join("members");
                memberProjects.select(project.get("id")).where(cb.equal(member.get("id"), user.getId()));
                return root.get("project").get("id").in(memberProjects);
            };
        };
    }

    public static Specification<Bug> matches(BugFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.status() != null) {
                predicates.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.priority() != null) {
                predicates.add(cb.equal(root.get("priority"), f.priority()));
            }
            if (f.severity() != null) {
                predicates.add(cb.equal(root.get("severity"), f.severity()));
            }
            if (f.projectId() != null) {
                predicates.add(cb.equal(root.get("project").get("id"), f.projectId()));
            }
            if (Boolean.TRUE.equals(f.unassigned())) {
                predicates.add(cb.isNull(root.get("assignedTo")));
            } else if (f.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assignedTo").get("id"), f.assigneeId()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String keyword = f.q().trim().toLowerCase(Locale.ROOT);
                String like = "%" + keyword + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("title")), like));
                any.add(cb.like(cb.lower(root.get("description")), like));
                String digits = keyword.startsWith("#") ? keyword.substring(1) : keyword;
                if (digits.matches("\\d{1,18}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(digits)));
                }
                predicates.add(cb.or(any.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
