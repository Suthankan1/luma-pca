output "endpoint" {
  value = aws_db_instance.this.address
}

output "master_secret_arn" {
  description = "Secrets Manager secret RDS manages for the master user."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "instance_id" {
  value = aws_db_instance.this.identifier
}
