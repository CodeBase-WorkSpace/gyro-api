package com.gyro.api.user.application

import java.util.UUID

interface UserService {
    fun getProfile(userId: UUID): UserProfileView
    fun updateProfile(userId: UUID, command: UpdateUserProfileCommand): UserProfileView
    fun markOnboardingWelcomeSeen(userId: UUID)
    fun acknowledgeCalculatorRerunPrompt(userId: UUID)
    fun deactivateCurrentUser(userId: UUID)
}
