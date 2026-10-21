variable "network_name" {
  description = "Nom du réseau Docker partagé avec Postgres et Keycloak"
  type        = string
  default     = "medlink"
}

variable "app_image" {
  description = "Image Docker immuable de l'API (ex: ghcr.io/assali-mohamed-ali/api-medlink@sha256:...)"
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
  description = "JDBC URL Postgres joignable depuis le conteneur"
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

variable "keycloak_public_url" {
  description = "URL de Keycloak vue par le navigateur (ex: https://auth.example.com)"
  type        = string
}

variable "keycloak_internal_url" {
  description = "URL de Keycloak vue par le conteneur API (ex: http://keycloak:8080)"
  type        = string
}

variable "keycloak_realm" {
  description = "Realm Keycloak"
  type        = string
  default     = "medlink"
}

variable "keycloak_client_id" {
  description = "Client confidentiel utilisé pour le login (password grant)"
  type        = string
  default     = "medlink-bff"
}

variable "keycloak_client_secret" {
  description = "Secret du client medlink-bff"
  type        = string
  sensitive   = true
}

variable "keycloak_admin_client_id" {
  description = "Client de service utilisé pour les appels admin Keycloak"
  type        = string
  default     = "medlink-admin"
}

variable "keycloak_admin_client_secret" {
  description = "Secret du client de service medlink-admin"
  type        = string
  sensitive   = true
}

variable "mail_host" {
  description = "Serveur SMTP de production"
  type        = string
}

variable "mail_port" {
  description = "Port SMTP de production (TLS)"
  type        = number
  default     = 587
}

variable "mail_username" {
  description = "Utilisateur SMTP"
  type        = string
  sensitive   = true
}

variable "mail_password" {
  description = "Mot de passe SMTP"
  type        = string
  sensitive   = true
}

variable "mail_from" {
  description = "Expéditeur des mails MedLink"
  type        = string
}

variable "app_frontend_url" {
  description = "URL du frontend React (liens dans les mails)"
  type        = string
}

variable "cors_allowed_origins" {
  description = "Origines autorisées par CORS, séparées par des virgules"
  type        = string
}
