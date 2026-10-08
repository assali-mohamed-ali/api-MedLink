variable "network_name" {
  description = "Nom du réseau Docker"
  type        = string
  default     = "medlink"
}

variable "app_image" {
  description = "Image Docker de l'API (ex: ghcr.io/assali-mohamed-ali/api-medlink:sha)"
  type        = string
}

variable "app_container_name" {
  description = "Nom du conteneur applicatif"
  type        = string
  default     = "api-medlink"
}

variable "app_external_port" {
  description = "Port hôte exposé pour l'API"
  type        = number
  default     = 8080
}

variable "db_url" {
  description = "JDBC URL Postgres"
  type        = string
}

variable "db_username" {
  description = "User Postgres"
  type        = string
  sensitive   = true
}

variable "db_password" {
  description = "Password Postgres"
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "Secret JWT (min 32 chars)"
  type        = string
  sensitive   = true
}
