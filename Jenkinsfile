pipeline {
    agent any

    environment {
        APP_NAME = 'tcg-store-backend'
        DOCKER_IMAGE = "tcg-store-backend:${BUILD_NUMBER}"
    }

    stages {
        stage('Checkout SCM') {
            steps {
                echo '=== Stage 1: Checkout Source Code ==='
                checkout scm
            }
        }

        stage('Compile & Static Analysis') {
            steps {
                echo '=== Stage 2: Compile Java Source Code ==='
                sh 'chmod +x ./mvnw || true'
                sh './mvnw clean compile -DskipTests'
            }
        }

        stage('Run Unit & Integration Tests') {
            steps {
                echo '=== Stage 3: Execute Unit & Admin Platform Tests ==='
                sh './mvnw test'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Package Application') {
            steps {
                echo '=== Stage 4: Package Spring Boot Executable JAR ==='
                sh './mvnw package -DskipTests'
                archiveArtifacts artifacts: '**/target/*.jar', fingerprint: true, allowEmptyArchive: true
            }
        }

        stage('Build Docker Image') {
            steps {
                echo '=== Stage 5: Build Production Docker Image ==='
                sh "docker build -t ${DOCKER_IMAGE} -t tcg-store-backend:latest ."
            }
        }
    }

    post {
        success {
            echo "✅ [SUCCESS] Pipeline for ${env.APP_NAME} (Build #${env.BUILD_NUMBER}) completed successfully."
        }
        failure {
            echo "❌ [FAILURE] Pipeline for ${env.APP_NAME} (Build #${env.BUILD_NUMBER}) failed. Please inspect console logs."
        }
    }
}
