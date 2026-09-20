package com.ghostlock.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class OnboardingAdapter : RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

    private val pages = listOf(
        Page(
            title = "FlickLock",
            subtitle = "Snap your screen shut",
            description = "Заблокируй экран одним движением. Просто, быстро, надёжно."
        ),
        Page(
            title = "Естественная защита",
            subtitle = "Никаких кнопок",
            description = "Наклони телефон к себе или поверни экраном вниз — экран заблокируется сам."
        ),
        Page(
            title = "Нужны разрешения",
            subtitle = "Только необходимое",
            description = "Приложению нужны: игнорирование оптимизации батареи и служба специальных возможностей. Больше ничего."
        ),
        Page(
            title = "Служба специальных возможностей",
            subtitle = "Одна функция — одна цель",
            description = "Служба используется только для блокировки экрана. Она не читает содержимое экрана и не собирает данные."
        )
    )

    data class Page(val title: String, val subtitle: String, val description: String)

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.pageTitle)
        val subtitle: TextView = view.findViewById(R.id.pageSubtitle)
        val description: TextView = view.findViewById(R.id.pageDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        holder.title.text = page.title
        holder.subtitle.text = page.subtitle
        holder.description.text = page.description
    }

    override fun getItemCount(): Int = pages.size
}