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

# Postgres and Keycloak are not created here: they are shared dependencies that
# must be reachable from this container. If they run on the same Docker host,
# attach them to the network named by var.network_name (see terraform/README.md).
resource "docker_container" "app" {
  name    = var.app_container_name
  image   = docker_image.app.image_id
  restart = "unless-stopped"

  networks_advanced {
    name = docker_network.medlink.name
  }

  ports {
    internal = 8080
    external = var.app_external_port
  }

  # Keep this list in step with application-prod.yml, application.properties
  # and the production section of README.md. scripts/ci/check-prod-config.sh
  # checks it in CI; no JWT_SECRET remains (Keycloak owns identity).
  env = [
    "SPRING_PROFILES_ACTIVE=prod",
    "SPRING_DATASOURCE_URL=${var.db_url}",
    "SPRING_DATASOURCE_USERNAME=${var.db_username}",
    "SPRING_DATASOURCE_PASSWORD=${var.db_password}",
    "KEYCLOAK_PUBLIC_URL=${var.keycloak_public_url}",
    "KEYCLOAK_INTERNAL_URL=${var.keycloak_internal_url}",
    "KEYCLOAK_REALM=${var.keycloak_realm}",
    "KEYCLOAK_CLIENT_ID=${var.keycloak_client_id}",
    "KEYCLOAK_CLIENT_SECRET=${var.keycloak_client_secret}",
    "KEYCLOAK_ADMIN_CLIENT_ID=${var.keycloak_admin_client_id}",
    "KEYCLOAK_ADMIN_CLIENT_SECRET=${var.keycloak_admin_client_secret}",
    "MAIL_HOST=${var.mail_host}",
    "MAIL_PORT=${var.mail_port}",
    "MAIL_USERNAME=${var.mail_username}",
    "MAIL_PASSWORD=${var.mail_password}",
    "MAIL_FROM=${var.mail_from}",
    "APP_FRONTEND_URL=${var.app_frontend_url}",
    "CORS_ALLOWED_ORIGINS=${var.cors_allowed_origins}",
  ]

  healthcheck {
    test         = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep UP"]
    interval     = "30s"
    timeout      = "5s"
    retries      = 3
    start_period = "60s"
  }
}
