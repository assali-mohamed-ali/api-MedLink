package com.example.apimedlink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One of the MedLink clinics (Sousse, Sfax, Tunis) where practitioners hold their slots. */
@Entity
@Table(name = "clinics")
@Getter
@Setter
@NoArgsConstructor
public class Clinic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String city;

    public Clinic(String name, String city) {
        this.name = name;
        this.city = city;
    }
}
