package com.rufit.profile;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

  public enum Experience {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
  }

  public enum Goal {
    GENERAL_FITNESS,
    STRENGTH,
    MUSCLE_GAIN,
    WEIGHT_MANAGEMENT,
  }

  @Id
  @Column(length = 100)
  String ownerName;

  @Column(nullable = false)
  UUID creationKey;

  @Column(nullable = false, length = 30)
  String nickname;

  @Column(nullable = false, length = 500)
  String bio;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  Experience experience;

  @ElementCollection
  @CollectionTable(name = "profile_goals", joinColumns = @JoinColumn(name = "owner_name"))
  @Enumerated(EnumType.STRING)
  @Column(name = "goal", length = 30, nullable = false)
  Set<Goal> goals = new HashSet<>();

  @Version
  Long version;

  protected UserProfile() {}

  UserProfile(String owner, UUID key, ProfileController.Fields fields) {
    ownerName = owner;
    creationKey = key;
    replace(fields);
  }

  void replace(ProfileController.Fields fields) {
    if (
      fields.nickname() == null ||
      fields.nickname().trim().length() < 2 ||
      fields.nickname().trim().length() > 30 ||
      fields.bio() == null ||
      fields.bio().length() > 500 ||
      fields.goals() == null ||
      fields.goals().size() > 4 ||
      fields.goals().stream().anyMatch(Objects::isNull)
    ) throw new IllegalArgumentException("닉네임·소개·운동 목표를 확인해 주세요.");
    nickname = fields.nickname().trim();
    bio = fields.bio();
    experience = fields.experience();
    goals.clear();
    goals.addAll(fields.goals());
  }
}
