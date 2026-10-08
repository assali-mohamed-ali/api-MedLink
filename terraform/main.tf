terraform {
  required_version = ">= 1.9.0"
  required_providers {
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 3.0"
    }
  }
}

provider "docker" {}

resource "docker_network" "medlink" {
  name = var.network_name
}

resource "docker_image" "app" {
  name         = var.app_image
  keep_locally = false
}

resource "docker_container" "app" {
  name  = var.app_container_name
  image = docker_image.app.image_id

  networks_advanced {
    name = docker_network.medlink.name
  }

  ports {
    internal = 8080
    external = var.app_external_port
  }

  env = [
    "SPRING_PROFILES_ACTIVE=prod",
    "SPRING_DATASOURCE_URL=${var.db_url}",
    "SPRING_DATASOURCE_USERNAME=${var.db_username}",
    "SPRING_DATASOURCE_PASSWORD=${var.db_password}",
    "JWT_SECRET=${var.jwt_secret}",
  ]

  healthcheck {
    test         = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep UP"]
    interval     = "30s"
    timeout      = "5s"
    retries      = 3
    start_period = "60s"
  }
}
