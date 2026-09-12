package com.rufit.profile;

import org.springframework.data.jpa.repository.JpaRepository;
interface ProfileRepository extends JpaRepository<UserProfile,String> {}
