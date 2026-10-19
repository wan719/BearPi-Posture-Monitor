package com.bearpi.posture.repository;

import com.bearpi.posture.entity.PostureEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostureEventRepository extends JpaRepository<PostureEvent, Long> { }
