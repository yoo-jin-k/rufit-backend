package com.rufit.profile;

import com.rufit.workout.WorkoutConflict;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ProfileService {

  private final ProfileRepository repository;

  ProfileService(ProfileRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ProfileController.View get(String owner) {
    return ProfileController.View.from(owned(owner));
  }

  public ProfileController.View create(String owner, UUID key, ProfileController.Fields fields) {
    var existing = repository.findById(owner);
    if (existing.isPresent()) {
      if (!key.equals(existing.get().creationKey)) throw new WorkoutConflict(
        "프로필이 이미 있습니다. 수정으로 진행해 주세요."
      );
      return ProfileController.View.from(existing.get());
    }
    return ProfileController.View.from(
      repository.saveAndFlush(new UserProfile(owner, key, fields))
    );
  }

  public ProfileController.View update(String owner, ProfileController.Edit edit) {
    UserProfile profile = owned(owner);
    if (!Objects.equals(profile.version, edit.version())) throw new WorkoutConflict(
      "프로필이 변경되었습니다. 다시 조회해 주세요."
    );
    profile.replace(edit.profile());
    return ProfileController.View.from(repository.saveAndFlush(profile));
  }

  private UserProfile owned(String owner) {
    return repository
      .findById(owner)
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "프로필을 아직 만들지 않았습니다.")
      );
  }
}
