package main

import (
	"log"

	"koharu-skin/internal/app"
)

func main() {
	router := app.NewRouter()
	if err := router.Run("127.0.0.1:8080"); err != nil {
		log.Fatalf("启动服务失败：%v", err)
	}
}
