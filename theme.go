package main

import (
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

type androidTheme struct{}

var _ fyne.Theme = (*androidTheme)(nil)

// Android 16 / Wireguard Dark Style Colors
var (
	colorBackground      = color.RGBA{R: 0, G: 0, B: 0, A: 255}             // Deep Black
	colorSurface         = color.RGBA{R: 28, G: 28, B: 30, A: 255}          // Dark Gray (Cards/Input)
	colorPrimary         = color.RGBA{R: 64, G: 169, B: 255, A: 255}        // Android Blue
	colorSuccess         = color.RGBA{R: 76, G: 175, B: 80, A: 255}         // Green
	colorText            = color.RGBA{R: 240, G: 240, B: 240, A: 255}       // White
	colorPlaceholder     = color.RGBA{R: 120, G: 120, B: 120, A: 255}       // Grey
	colorInputBackground = color.RGBA{R: 20, G: 20, B: 22, A: 255}          // Slightly lighter than bg for logs
)

func (m androidTheme) Color(name fyne.ThemeColorName, variant fyne.ThemeVariant) color.Color {
	switch name {
	case theme.ColorNameBackground:
		return colorBackground
	case theme.ColorNameButton, theme.ColorNameDisabledButton:
		return colorSurface
	case theme.ColorNameInputBackground:
		return colorInputBackground
	// Исправлено: Убрали ColorNameButtonForeground, добавили ColorNameDisabled
	case theme.ColorNameForeground:
		return colorText
	case theme.ColorNameDisabled:
		// Возвращаем белый цвет даже для отключенных виджетов (лога), чтобы он был читаем
		return colorText
	case theme.ColorNamePrimary:
		return colorPrimary
	case theme.ColorNameScrollBar:
		return colorPrimary
	case theme.ColorNamePlaceHolder:
		return colorPlaceholder
	default:
		return theme.DefaultTheme().Color(name, variant)
	}
}

func (m androidTheme) Icon(name fyne.ThemeIconName) fyne.Resource {
	return theme.DefaultTheme().Icon(name)
}

func (m androidTheme) Font(style fyne.TextStyle) fyne.Resource {
	return theme.DefaultTheme().Font(style)
}

func (m androidTheme) Size(name fyne.ThemeSizeName) float32 {
	return theme.DefaultTheme().Size(name)
}
