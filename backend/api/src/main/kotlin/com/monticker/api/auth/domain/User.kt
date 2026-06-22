package com.monticker.api.auth.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password_hash")
    var passwordHash: String? = null,

    @Column(nullable = false)
    var nickname: String,

    @Column(nullable = false)
    val provider: String = "LOCAL",

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.USER,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
)

enum class UserRole { USER, ADMIN, SYSTEM_WORKER }
