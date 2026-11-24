package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A patient is a user with a medical profile (and only a patient: a person never holds two
 * roles). Same id as its {@code users} row, the Keycloak id: {@code patients.id} references
 * {@code users.id}. Saving a patient writes both rows.
 */
@Entity
@Table(name = "patients")
@PrimaryKeyJoinColumn(name = "id")
@Getter
@Setter
@NoArgsConstructor
public class Patient extends User {

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false)
    private LocalDate birthDate;

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String address;

    /** The profile of a sign-up; id, username and email are set once Keycloak has the account. */
    public Patient(String firstName, String lastName, LocalDate birthDate, String phone, String address) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.phone = phone;
        this.address = address;
    }
}
